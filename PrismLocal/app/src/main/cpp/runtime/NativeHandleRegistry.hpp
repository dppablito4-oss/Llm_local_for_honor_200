#pragma once

#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <utility>

namespace llmhost {

// JNI handles are monotonically increasing IDs, never raw addresses. Each JNI
// call holds a lease until it returns; retirement rejects new calls immediately
// without freeing an object that an in-flight call is still using. Never hold
// the registry mutex while calling native work, cancellation or destructors.
template <typename T>
class NativeHandleRegistry {
public:
    using Handle = int64_t;

    Handle insert(std::shared_ptr<T> instance) {
        if (!instance) return 0;
        std::lock_guard<std::mutex> lock(mutex_);
        if (next_ == std::numeric_limits<Handle>::max()) return 0;
        const Handle handle = next_++;
        instances_.emplace(handle, std::move(instance));
        return handle;
    }

    std::shared_ptr<T> acquire(Handle handle) const {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = instances_.find(handle);
        return found == instances_.end() ? nullptr : found->second;
    }

    std::shared_ptr<T> retire(Handle handle) {
        std::shared_ptr<T> instance;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto found = instances_.find(handle);
            if (found == instances_.end()) return nullptr;
            instance = std::move(found->second);
            instances_.erase(found);
        }
        return instance;
    }

private:
    mutable std::mutex mutex_;
    Handle next_ = 1;
    std::unordered_map<Handle, std::shared_ptr<T>> instances_;
};

} // namespace llmhost
