#include "unary-ops.h"

static inline float op_abs(float x) {
    return fabsf(x);
}

static inline float op_sgn(float x) {
    return (x > 0.f) ? 1.f : ((x < 0.f) ? -1.f : 0.f);
}

static inline float op_neg(float x) {
    return -x;
}

static inline float op_step(float x) {
    return (x > 0.f) ? 1.f : 0.f;
}

static inline float op_tanh(float x) {
    return tanhf(x);
}

static inline float op_elu(float x) {
    return (x > 0.f) ? x : expm1f(x);
}

static inline float op_relu(float x) {
    return (x > 0.f) ? x : 0.f;
}

static inline float op_sigmoid(float x) {
    return 1.f / (1.f + expf(-x));
}

static inline float op_hardsigmoid(float x) {
    return fminf(1.0f, fmaxf(0.0f, (x + 3.0f) / 6.0f));
}

static inline float op_exp(float x) {
    return expf(x);
}

static inline float op_hardswish(float x) {
    return x * fminf(1.0f, fmaxf(0.0f, (x + 3.0f) / 6.0f));
}

static inline float op_sqr(float x) {
    return x * x;
}

static inline float op_sqrt(float x) {
    return sqrtf(x);
}

static inline float op_xielu(float x, float alpha_n, float alpha_p, float beta, float eps) {
    if (x > 0.0f) {
        return alpha_p * x * x + beta * x;
    } else {
        const float min_x_eps = fminf(x, eps);
        return (expm1f(min_x_eps) - x) * alpha_n + beta * x;
    }
}

static inline float op_sin(float x) {
    return sinf(x);
}

static inline float op_cos(float x) {
    return cosf(x);
}

static inline float op_log(float x) {
    return logf(x);
}

static inline float op_expm1(float x) {
    return expf(x) - 1.0f;
}

static inline float op_softplus(float x) {
    return (x > 20.0f) ? x : logf(1.0f + expf(x));
}

static inline float op_floor(float x) {
    return floorf(x);
}

static inline float op_ceil(float x) {
    return ceilf(x);
}

static inline float op_round(float x) {
    return roundf(x);
}

static inline float op_trunc(float x) {
    return truncf(x);
}

template <float (*op)(float), typename src0_t, typename dst_t>
static inline void vec_unary_op(int64_t n, dst_t * y, const src0_t * x) {
    constexpr auto src0_to_f32 = type_conversion_table<src0_t>::to_f32;
    constexpr auto f32_to_dst  = type_conversion_table<dst_t >::from_f32;

    for (int i = 0; i < n; i++) {
        y[i] = f32_to_dst(op(src0_to_f32(x[i])));
    }
}

template <float (*op)(float), typename src0_t, typename dst_t>
static void apply_unary_op(const ggml_compute_params * params, ggml_tensor * dst) {
    const ggml_tensor * src0 = dst->src[0];

    GGML_ASSERT(ggml_are_same_shape(src0, dst));

    GGML_TENSOR_UNARY_OP_LOCALS

    const auto [ir0, ir1] = get_thread_range(params, src0);

    for (int64_t ir = ir0; ir < ir1; ++ir) {
        const int64_t i03 = ir/(ne02*ne01);
        const int64_t i02 = (ir - i03*ne02*ne01)/ne01;
        const int64_t i01 = (ir - i03*ne02*ne01 - i02*ne01);

        dst_t        * dst_ptr  = (dst_t  *)       ((char *)       dst->data  + i03*nb3  + i02*nb2  + i01*nb1 );
        const src0_t * src0_ptr = (const src0_t *) ((const char *) src0->data + i03*nb03 + i02*nb02 + i01*nb01);

        if (nb0 == sizeof(dst_t) && nb00 == sizeof(src0_t)) {
            vec_unary_op<op>(ne0, dst_ptr, src0_ptr);
        } else {
            constexpr auto src0_to_f32 = type_conversion_table<src0_t>::to_f32;
            constexpr auto f32_to_dst  = type_conversion_table<dst_t >::from_f32;
            for (int64_t i00 = 0; i00 < ne0; ++i00) {
                const src0_t * s0 = (const src0_t *)((const char *)src0_ptr + i00*nb00);
                dst_t * d = (dst_t *)((char *)dst_ptr + i00*nb0);
                *d = f32_to_dst(op(src0_to_f32(*s0)));
            }
        }
    }
}

// Extend vec_unary_op to support functors
template <typename Op, typename src0_t, typename dst_t>
static inline void vec_unary_op_functor(int64_t n, dst_t * y, const src0_t * x, Op op) {
    constexpr auto src0_to_f32 = type_conversion_table<src0_t>::to_f32;
    constexpr auto f32_to_dst  = type_conversion_table<dst_t >::from_f32;

    for (int i = 0; i < n; i++) {
        y[i] = f32_to_dst(op(src0_to_f32(x[i])));
    }
}

// Extend apply_unary_op to support functors
template <typename Op, typename src0_t, typename dst_t>
static void apply_unary_op_functor(const ggml_compute_params * params, ggml_tensor * dst, Op op) {
    const ggml_tensor * src0 = dst->src[0];

    GGML_ASSERT(ggml_are_same_shape(src0, dst));

    GGML_TENSOR_UNARY_OP_LOCALS

    const auto [ir0, ir1] = get_thread_range(params, src0);

    for (int64_t ir = ir0; ir < ir1; ++ir) {
        const int64_t i03 = ir/(ne02*ne01);
        const int64_t i02 = (ir - i03*ne02*ne01)/ne01;
        const int64_t i01 = (ir - i03*ne02*ne01 - i02*ne01);

        dst_t        * dst_ptr  = (dst_t  *)       ((char *)       dst->data  + i03*nb3  + i02*nb2  + i01*nb1 );
        const src0_t * src0_ptr = (const src0_t *) ((const char *) src0->data + i03*nb03 + i02*nb02 + i01*nb01);

        if (nb0 == sizeof(dst_t) && nb00 == sizeof(src0_t)) {
            vec_unary_op_functor(ne0, dst_ptr, src0_ptr, op);
        } else {
            constexpr auto src0_to_f32 = type_conversion_table<src0_t>::to_f32;
            constexpr auto f32_to_dst  = type_conversion_table<dst_t >::from_f32;
            for (int64_t i00 = 0; i00 < ne0; ++i00) {
                const src0_t * s0 = (const src0_t *)((const char *)src0_ptr + i00*nb00);
                dst_t * d = (dst_t *)((char *)dst_ptr + i00*nb0);
                *d = f32_to_dst(op(src0_to_f32(*s0)));
            }
        }
    }
}

// Generic dispatcher for functors
template <typename Op>
static void unary_op_functor(const ggml_compute_params * params, ggml_tensor * dst, Op op) {
    const ggml_tensor * src0 = dst->src[0];

    auto dispatch_dst = [&](auto src0_tag) {
        using S0 = decltype(src0_tag);
        switch (dst->type) {
            case GGML_TYPE_F32:  apply_unary_op_functor<Op, S0, float>(params, dst, op); break;
            case GGML_TYPE_F16:  apply_unary_op_functor<Op, S0, ggml_fp16_t>(params, dst, op); break;
            case GGML_TYPE_BF16: apply_unary_op_functor<Op, S0, ggml_bf16_t>(params, dst, op); break;
            default:
                GGML_ABORT("%s: unsupported dst type: %s (src0: %s)\n", __func__,
                    ggml_type_name(dst->type), ggml_type_name(src0->type));
        }
    };

    switch (src0->type) {
        case GGML_TYPE_F32:  dispatch_dst(float{}); break;
        case GGML_TYPE_F16:  dispatch_dst(ggml_fp16_t{}); break;
        case GGML_TYPE_BF16: dispatch_dst(ggml_bf16_t{}); break;
        default:
            GGML_ABORT("%s: unsupported src0 type: %s (dst: %s)\n", __func__,
                ggml_type_name(src0->type), ggml_type_name(dst->type));
    }
}

template <float (*op)(float)>
static void unary_op(const ggml_compute_params * params, ggml_tensor * dst) {
    const ggml_tensor * src0 = dst->src[0];

    auto dispatch_dst = [&](auto src0_tag) {
        using S0 = decltype(src0_tag);
        switch (dst->type) {
            case GGML_TYPE_F32:  apply_unary_op<op, S0, float>(params, dst); break;
            case GGML_TYPE_F16:  apply_unary_op<op, S0, ggml_fp16_t>(params, dst); break;
            case GGML_TYPE_BF16: apply_unary_op<op, S0, ggml_bf16_t>(params, dst); break;
            default:
                GGML_ABORT("%s: unsupported dst type: %s (src0: %s)\n", __func__,
                    ggml_type_name(dst->type), ggml_type_name(src0->type));
        }
    };

    switch (src0->type) {
        case GGML_TYPE_F32:  dispatch_dst(float{}); break;
        case GGML_TYPE_F16:  dispatch_dst(ggml_fp16_t{}); break;
        case GGML_TYPE_BF16: dispatch_dst(ggml_bf16_t{}); break;
        default:
            GGML_ABORT("%s: unsupported src0 type: %s (dst: %s)\n", __func__,
                ggml_type_name(src0->type), ggml_type_name(dst->type));
    }
}

template <float (*op)(float, ggml_tensor *)>
static void unary_op_params(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op_functor(params, dst, [dst](float x) { return op(x, dst); });
}

void ggml_compute_forward_abs(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_abs>(params, dst);
}

void ggml_compute_forward_sgn(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_sgn>(params, dst);
}

void ggml_compute_forward_neg(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_neg>(params, dst);
}

void ggml_compute_forward_step(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_step>(params, dst);
}

void ggml_compute_forward_tanh(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_tanh>(params, dst);
}

void ggml_compute_forward_elu(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_elu>(params, dst);
}

void ggml_compute_forward_relu(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_relu>(params, dst);
}

void ggml_compute_forward_sigmoid(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_sigmoid>(params, dst);
}

void ggml_compute_forward_hardsigmoid(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_hardsigmoid>(params, dst);
}

void ggml_compute_forward_exp(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_exp>(params, dst);
}

void ggml_compute_forward_hardswish(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_hardswish>(params, dst);
}

void ggml_compute_forward_sqr(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_sqr>(params, dst);
}

void ggml_compute_forward_sqrt(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_sqrt>(params, dst);
}

void ggml_compute_forward_sin(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_sin>(params, dst);
}

void ggml_compute_forward_cos(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_cos>(params, dst);
}

void ggml_compute_forward_log(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_log>(params, dst);
}

void ggml_compute_forward_expm1(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_expm1>(params, dst);
}

void ggml_compute_forward_softplus(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_softplus>(params, dst);
}

void ggml_compute_forward_floor(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_floor>(params, dst);
}

void ggml_compute_forward_ceil(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_ceil>(params, dst);
}

void ggml_compute_forward_round(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_round>(params, dst);
}

void ggml_compute_forward_trunc(const ggml_compute_params * params, ggml_tensor * dst) {
    unary_op<op_trunc>(params, dst);
}

void ggml_compute_forward_xielu(const ggml_compute_params * params, ggml_tensor * dst) {
    const float alpha_n = ggml_get_op_params_f32(dst, 1);
    const float alpha_p = ggml_get_op_params_f32(dst, 2);
    const float beta = ggml_get_op_params_f32(dst, 3);
    const float eps = ggml_get_op_params_f32(dst, 4);

    const auto xielu_op_params = [alpha_n, alpha_p, beta, eps](float f) {
        return op_xielu(f, alpha_n, alpha_p, beta, eps);
    };

    unary_op_functor(params, dst, xielu_op_params);
}

