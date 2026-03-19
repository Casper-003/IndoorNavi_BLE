#include <jni.h>
#include <cmath>
#include <vector>
#include <algorithm>
#include <unordered_set>

// =====================================================================================
// 内部数据结构
// =====================================================================================
struct Pt {
    float x, z;
};

// =====================================================================================
// 凸包：Andrew's Monotone Chain（逆时针）
// =====================================================================================
static float cross(const Pt& o, const Pt& a, const Pt& b) {
    return (a.x - o.x) * (b.z - o.z) - (a.z - o.z) * (b.x - o.x);
}

static std::vector<Pt> convexHull(std::vector<Pt> pts) {
    int n = (int)pts.size();
    if (n <= 2) return pts;

    std::sort(pts.begin(), pts.end(), [](const Pt& a, const Pt& b) {
        return a.x < b.x || (a.x == b.x && a.z < b.z);
    });

    std::vector<Pt> h;
    // 下凸壳
    for (int i = 0; i < n; i++) {
        while (h.size() >= 2 && cross(h[h.size()-2], h[h.size()-1], pts[i]) <= 0)
            h.pop_back();
        h.push_back(pts[i]);
    }
    // 上凸壳
    int lower_size = (int)h.size();
    for (int i = n - 2; i >= 0; i--) {
        while ((int)h.size() > lower_size && cross(h[h.size()-2], h[h.size()-1], pts[i]) <= 0)
            h.pop_back();
        h.push_back(pts[i]);
    }
    h.pop_back(); // 去掉起点重复
    return h;
}

// =====================================================================================
// PCA 主轴对齐：让房间长边竖直
// =====================================================================================
static std::vector<Pt> alignToScreen(const std::vector<Pt>& pts) {
    int n = (int)pts.size();
    if (n < 2) return pts;

    double cx = 0, cz = 0;
    for (auto& p : pts) { cx += p.x; cz += p.z; }
    cx /= n; cz /= n;

    double cxx = 0, czz = 0, cxz = 0;
    for (auto& p : pts) {
        double dx = p.x - cx, dz = p.z - cz;
        cxx += dx * dx; czz += dz * dz; cxz += dx * dz;
    }

    double diff = (cxx - czz) / 2.0;
    double eigenAngle = 0.5 * std::atan2(2.0 * cxz, diff * 2.0 + 1e-10);
    double axisX = std::cos(eigenAngle);
    double axisZ = std::sin(eigenAngle);

    double angleToY = std::atan2(axisX, axisZ);
    double cosA = std::cos(-angleToY);
    double sinA = std::sin(-angleToY);

    std::vector<Pt> result;
    result.reserve(n);
    for (auto& p : pts) {
        double dx = p.x - cx, dz = p.z - cz;
        result.push_back({
            (float)(cx + dx * cosA - dz * sinA),
            (float)(cz + dx * sinA + dz * cosA)
        });
    }
    return result;
}

// =====================================================================================
// JNI 入口：extractBoundaryPolygon
// 输入：points = [x0,z0, x1,z1, ...], resolution = 栅格大小（默认 0.15m）
// 输出：[x0,z0, x1,z1, ...] 凸包顶点（PCA 对齐后）
// =====================================================================================
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_echo_MapProcessor_extractBoundaryPolygon(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray points,
        jfloat resolution)
{
    jint len = env->GetArrayLength(points);
    if (len < 6) return env->NewFloatArray(0); // 至少 3 个点

    jfloat* raw = env->GetFloatArrayElements(points, nullptr);

    int count = len / 2;

    // ── 1. 网格去重（0.1m 分辨率）─────────────────────────────────────────────
    const float DEDUP_RES = 0.1f;
    std::unordered_set<long long> dedupSet;
    std::vector<Pt> deduped;
    deduped.reserve(count);

    for (int i = 0; i < count; i++) {
        float x = raw[i * 2];
        float z = raw[i * 2 + 1];
        long long cx = (long long)std::floor(x / DEDUP_RES);
        long long cz = (long long)std::floor(z / DEDUP_RES);
        long long key = (cx << 32) | (cz & 0xFFFFFFFFL);
        if (dedupSet.insert(key).second) {
            deduped.push_back({x, z});
        }
    }

    env->ReleaseFloatArrayElements(points, raw, JNI_ABORT);

    if ((int)deduped.size() < 3) return env->NewFloatArray(0);

    // ── 2. 栅格化（resolution 分辨率）──────────────────────────────────────────
    float minX = deduped[0].x, minZ = deduped[0].z;
    for (auto& p : deduped) {
        if (p.x < minX) minX = p.x;
        if (p.z < minZ) minZ = p.z;
    }

    std::unordered_set<long long> occupied;
    for (auto& p : deduped) {
        int gx = (int)std::floor((p.x - minX) / resolution);
        int gz = (int)std::floor((p.z - minZ) / resolution);
        long long key = ((long long)gx << 32) | ((long long)gz & 0xFFFFFFFFL);
        occupied.insert(key);
    }

    // ── 3. 边缘格提取 ───────────────────────────────────────────────────────────
    const int dx8[] = {-1,-1,-1, 0, 0, 1, 1, 1};
    const int dz8[] = {-1, 0, 1,-1, 1,-1, 0, 1};

    std::vector<Pt> edgeCells;
    for (auto& key : occupied) {
        int gx = (int)(key >> 32);
        int gz = (int)(key & 0xFFFFFFFFL);
        bool isEdge = false;
        for (int d = 0; d < 8; d++) {
            int nx = gx + dx8[d], nz = gz + dz8[d];
            long long nk = ((long long)nx << 32) | ((long long)nz & 0xFFFFFFFFL);
            if (occupied.find(nk) == occupied.end()) { isEdge = true; break; }
        }
        if (isEdge) {
            float wx = minX + (gx + 0.5f) * resolution;
            float wz = minZ + (gz + 0.5f) * resolution;
            edgeCells.push_back({wx, wz});
        }
    }

    if ((int)edgeCells.size() < 3) return env->NewFloatArray(0);

    // ── 4. 凸包 ─────────────────────────────────────────────────────────────────
    std::vector<Pt> hull = convexHull(edgeCells);

    // ── 5. PCA 对齐 ──────────────────────────────────────────────────────────────
    std::vector<Pt> aligned = alignToScreen(hull);

    // ── 6. 打包返回 ──────────────────────────────────────────────────────────────
    int outCount = (int)aligned.size();
    jfloatArray result = env->NewFloatArray(outCount * 2);
    std::vector<float> buf(outCount * 2);
    for (int i = 0; i < outCount; i++) {
        buf[i * 2]     = aligned[i].x;
        buf[i * 2 + 1] = aligned[i].z;
    }
    env->SetFloatArrayRegion(result, 0, outCount * 2, buf.data());
    return result;
}
