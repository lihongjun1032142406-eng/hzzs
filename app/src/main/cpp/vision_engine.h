#pragma once
#pragma once
#include <cstring>
#include "algorithm_runtime.h"
#include "algorithm_runtime.h"
#include "multicolor_detector.h"
#include "vision_types.h"

namespace hzzs {

/**
 * 引擎后端选择。
 *
 * DEFAULT = 现有主路径（sweet/bamboo/sea_salt 检测器）；
 * NATIVE_VISION = HZZS Native Vision 1.0（海盐走 vision_v3 SeaSaltV3Engine）。
 */
enum class VisionBackend : std::uint8_t {
    DEFAULT = 0,
    NATIVE_VISION = 1,
};

inline VisionBackend backend_from_profile(const AlgorithmRuntimeProfileNative& profile) noexcept {
    if (profile.backend_id[0] == '\0') return VisionBackend::DEFAULT;
    if (std::strcmp(profile.backend_id, kBackendNativeVision) == 0) {
        return VisionBackend::NATIVE_VISION;
    }
    return VisionBackend::DEFAULT;
}

/**
 * 视觉引擎入口（算法引擎调度 + 历史路径 fallback）。
 *
 * - 甜品/竹影：优先 legacy_main 主路径，过弱时启发式回退。
 * - 海盐 DEFAULT 后端：现有 sea_salt 主路径（analyze_sea_salt）。
 * - 海盐 NATIVE_VISION 后端：vision_v3 SeaSaltV3Engine（三槽 shadow/exact/fast）。
 *
 * 线程：JNI 层用互斥串行化 analyze / configure / reset。
 * 帧路径禁止读文件、解析 JSON、分配大型规则对象。
 */

/** 甜品工厂赛季分析（显式 params，供测试与回退路径）。 */
Result analyze_sweet(const FrameView& frame, int work_width, int enabled_kind_mask,
                     bool detect_player, float fixed_player_x_ratio,
                     const SceneAlgorithmParamsNative& params);

/** 竹影书屋赛季分析。 */
Result analyze_bamboo(const FrameView& frame, int work_width, int enabled_kind_mask,
                      bool detect_player, float fixed_player_x_ratio,
                      const SceneAlgorithmParamsNative& params);

/** 海盐客厅赛季分析（参数驱动主路径）。 */
Result analyze_sea_salt(const FrameView& frame, int work_width, int enabled_kind_mask,
                        bool detect_player, float fixed_player_x_ratio,
                        const SceneAlgorithmParamsNative& params,
                        std::vector<MulticolorDiag>* detail_out = nullptr);

/** 海盐客厅赛季 Native Vision 1.0 后端（SeaSaltV3Engine 三槽引擎）。 */
Result analyze_sea_salt_sparse(const FrameView& frame, int work_width, int enabled_kind_mask,
                               bool detect_player, float fixed_player_x_ratio,
                               const SceneAlgorithmParamsNative& params,
                               std::vector<MulticolorDiag>* detail_out = nullptr);

/** 在分析末尾追加声明式多点找色检测结果（定义于 sea_salt_living_room.cpp）。 */
void append_multicolor_detections(Result& out, const FrameView& frame, int enabled_kind_mask,
                                  const SceneAlgorithmParamsNative& params,
                                  std::vector<MulticolorDiag>* detail_out = nullptr);

/**
 * 使用当前 AlgorithmRuntime 快照分析。
 * @param scene 0=甜品，1=竹影，2=海盐（与 Kotlin SceneId 序一致）
 * @param enable_stage_timing 开发者阶段耗时；关则 timing 全 0
 * @param enable_multicolor_diag 开发者多点找色明细；关则不采样
 * @param enable_filter_trace 开发者过滤原因；关则 filtered_out 为空
 */
Result analyze(int scene, const FrameView& frame, int work_width, int enabled_kind_mask,
               bool detect_player, float fixed_player_x_ratio,
               bool enable_stage_timing = false,
               bool enable_multicolor_diag = false,
               bool enable_filter_trace = false);

/** 显式传入 profile（宿主机 / 单测，不依赖进程内 runtime 单例）。 */
Result analyze_with_profile(int scene, const FrameView& frame, int work_width, int enabled_kind_mask,
                            bool detect_player, float fixed_player_x_ratio,
                            const AlgorithmRuntimeProfileNative& profile,
                            bool enable_stage_timing = false,
                            bool enable_multicolor_diag = false,
                            bool enable_filter_trace = false);

/** 清空引擎瞬时状态（不含算法 profile）。 */
void reset();

/** 位掩码是否启用某 Kind。 */
inline bool kind_enabled(int mask, Kind kind) {
    const int bit = static_cast<int>(kind);
    return bit >= 0 && bit < 31 && (mask & (1 << bit)) != 0;
}

}  // namespace hzzs
