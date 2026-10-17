#include "runtime/NativeHandleRegistry.hpp"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <latch>
#include <thread>
#include <vector>

static void require(bool ok, const char* message) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", message);
        std::abort();
    }
}

struct Resource {
    explicit Resource(std::atomic<int>& destroyed) : destroyed(destroyed) {}
    ~Resource() {
        if (on_destroy) on_destroy();
        destroyed.fetch_add(1);
    }
    std::atomic<int>& destroyed;
    std::function<void()> on_destroy;
    const int payload = 12345;
};

int main() {
    llmhost::NativeHandleRegistry<Resource> registry;
    std::atomic<int> destroyed{0};
    require(!registry.acquire(0), "zero must not resolve");
    require(!registry.acquire(-1), "invalid handles must not resolve");
    require(registry.insert(nullptr) == 0, "null resources must not allocate IDs");

    auto handle = registry.insert(std::make_shared<Resource>(destroyed));
    auto lease = registry.acquire(handle);
    auto retired = registry.retire(handle);
    require(!registry.acquire(handle), "retirement must reject new calls immediately");
    require(!registry.retire(handle), "double destruction must be idempotent");
    retired.reset();
    require(destroyed == 0, "in-flight JNI call must keep engine alive");
    require(lease->payload == 12345, "retired engine must remain usable by its lease");
    lease.reset();
    require(destroyed == 1, "last lease must destroy exactly once");

    auto next = registry.insert(std::make_shared<Resource>(destroyed));
    require(next > handle, "handle IDs must never be reused (ABA)");
    auto probe = registry.acquire(next);
    probe->on_destroy = [&] {
        require(!registry.acquire(next), "destructor must run after removal");
        // Reentrancy would deadlock if retirement destroyed inside the map lock.
        auto nested = registry.insert(std::make_shared<Resource>(destroyed));
        registry.retire(nested);
    };
    probe.reset();
    registry.retire(next);
    require(destroyed == 3, "reentrant destructor must complete outside registry lock");

    for (int round = 0; round < 200; ++round) {
        const auto id = registry.insert(std::make_shared<Resource>(destroyed));
        std::latch acquired(4);
        std::latch released(1);
        std::vector<std::thread> callers;
        for (int worker = 0; worker < 4; ++worker) {
            callers.emplace_back([&] {
                auto active = registry.acquire(id);
                require(active != nullptr, "call must acquire before destroy");
                acquired.count_down();
                released.wait();
                require(active->payload == 12345, "destroy must not invalidate active call");
                require(!registry.acquire(id), "stale handle must stay rejected");
            });
        }
        acquired.wait();
        const int before = destroyed.load();
        registry.retire(id);
        require(destroyed == before, "destroy must defer while all four calls are active");
        released.count_down();
        for (auto& caller : callers) caller.join();
        require(destroyed == before + 1, "concurrent retirement must free exactly once");
    }
    std::puts("PASS: native handle retirement, ABA rejection, reentrant free and 200 concurrent races");
}
