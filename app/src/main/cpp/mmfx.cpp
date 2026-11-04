// MYmusic Cutter sound engine (C++).
// Runs inside the native player's audio pipeline: every decoded block passes through here
// before it reaches the speaker or headphones. Nothing travels back and forth through the
// web page, so there is no feedback delay. The chain is the one from version 2.1:
// rumble > hum > bass > 8 bands > clarity > hiss > [click repair] > [tube amp] > [bass enhancer]
//   > [tape NR] > [B-decode] > [tube amp] > [bass enhancer] > [clarity HD]
//   > [dialogue + stereo width] > [wide 3D] > [virtualizer] > [volume leveler] > [limiter]
// (the four stages added in 4.8 - tape NR, B-decode, clarity HD, wide 3D - are skipped while at 0)
// When every setting is neutral the sound is copied through untouched.

#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace {

constexpr int NPARAM = 28;
// 0..7 bands, 8 bass, 9 clarity, 10 virtual, 11 hiss, 12 click, 13 hum, 14 rumble, 15 tube,
// 16 warmth, 17 dialog, 18 width, 19 bassx, 20 level, 21 limiter on, 22 neutral,
// 23 tape NR, 24 B-decode, 25 clarity HD, 26 wide 3D
std::atomic<float> gParam[NPARAM];
std::atomic<int> gAnalyse{0};

inline float clampf(float v, float a, float b) { return v < a ? a : (v > b ? b : v); }
inline double dbToGain(double db) { return std::pow(10.0, db / 20.0); }

void enableFlushToZero() {
#if defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    asm volatile("msr fpcr, %0" ::"r"(fpcr | (1ull << 24)));
#elif defined(__x86_64__) || defined(__i386__)
    uint32_t csr;
    asm volatile("stmxcsr %0" : "=m"(csr));
    csr |= 0x8040;
    asm volatile("ldmxcsr %0" ::"m"(csr));
#endif
}

// Biquad filters with the same formulas the web version used (Web Audio specification).
struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    double z1[2] = {0, 0}, z2[2] = {0, 0};
    inline float run(float x, int c) {
        double y = b0 * x + z1[c];
        z1[c] = b1 * x - a1 * y + z2[c];
        z2[c] = b2 * x - a2 * y;
        return (float)y;
    }
    void reset() { z1[0] = z1[1] = z2[0] = z2[1] = 0; }
    void norm(double B0, double B1, double B2, double A0, double A1, double A2) {
        b0 = B0 / A0; b1 = B1 / A0; b2 = B2 / A0; a1 = A1 / A0; a2 = A2 / A0;
    }
    // Q in dB for low/high-pass, like Web Audio
    void lowpass(double sr, double f, double qdb) {
        f = std::min(f, sr * 0.45);
        double w = 2 * M_PI * f / sr, cs = std::cos(w), al = std::sin(w) / (2 * std::pow(10.0, qdb / 20));
        norm((1 - cs) / 2, 1 - cs, (1 - cs) / 2, 1 + al, -2 * cs, 1 - al);
    }
    void highpass(double sr, double f, double qdb) {
        f = std::min(f, sr * 0.45);
        double w = 2 * M_PI * f / sr, cs = std::cos(w), al = std::sin(w) / (2 * std::pow(10.0, qdb / 20));
        norm((1 + cs) / 2, -(1 + cs), (1 + cs) / 2, 1 + al, -2 * cs, 1 - al);
    }
    void peaking(double sr, double f, double q, double gdb) {
        f = std::min(f, sr * 0.45);
        double A = std::pow(10.0, gdb / 40), w = 2 * M_PI * f / sr, cs = std::cos(w), al = std::sin(w) / (2 * q);
        norm(1 + al * A, -2 * cs, 1 - al * A, 1 + al / A, -2 * cs, 1 - al / A);
    }
    void lowshelf(double sr, double f, double gdb) {
        f = std::min(f, sr * 0.45);
        double A = std::pow(10.0, gdb / 40), w = 2 * M_PI * f / sr, cs = std::cos(w);
        double al = std::sin(w) / 2 * std::sqrt(2.0), sa = 2 * std::sqrt(A) * al;
        norm(A * ((A + 1) - (A - 1) * cs + sa), 2 * A * ((A - 1) - (A + 1) * cs), A * ((A + 1) - (A - 1) * cs - sa),
             (A + 1) + (A - 1) * cs + sa, -2 * ((A - 1) + (A + 1) * cs), (A + 1) + (A - 1) * cs - sa);
    }
    void highshelf(double sr, double f, double gdb) {
        f = std::min(f, sr * 0.45);
        double A = std::pow(10.0, gdb / 40), w = 2 * M_PI * f / sr, cs = std::cos(w);
        double al = std::sin(w) / 2 * std::sqrt(2.0), sa = 2 * std::sqrt(A) * al;
        norm(A * ((A + 1) + (A - 1) * cs + sa), -2 * A * ((A - 1) + (A + 1) * cs), A * ((A + 1) + (A - 1) * cs - sa),
             (A + 1) - (A - 1) * cs + sa, 2 * ((A - 1) - (A + 1) * cs), (A + 1) - (A - 1) * cs - sa);
    }
};

struct Delay {
    std::vector<float> b;
    uint32_t w = 0, mask = 0;
    void init(int minLen) {
        uint32_t n = 1;
        while (n < (uint32_t)minLen + 2) n <<= 1;
        b.assign(n, 0.f); mask = n - 1; w = 0;
    }
    inline void push(float x) { b[w & mask] = x; w++; }
    inline float tap(int d) const { return b[(w - 1 - d) & mask]; }
    void clear() { std::fill(b.begin(), b.end(), 0.f); }
};

struct Comb {
    std::vector<float> b; int i = 0; float store = 0;
    void init(int n) { b.assign(std::max(1, n), 0.f); i = 0; store = 0; }
    inline float run(float x, float fb, float damp) {
        float o = b[i];
        store = o * (1 - damp) + store * damp;
        b[i] = x + store * fb;
        if (++i >= (int)b.size()) i = 0;
        return o;
    }
};
struct Allpass {
    std::vector<float> b; int i = 0;
    void init(int n) { b.assign(std::max(1, n), 0.f); i = 0; }
    inline float run(float x) {
        float bo = b[i], o = -x + bo;
        b[i] = x + bo * 0.5f;
        if (++i >= (int)b.size()) i = 0;
        return o;
    }
};

constexpr int FFTN = 8192;

struct Engine {
    double sr = 48000;
    int inCh = 2;
    float S[NPARAM];     // smoothed settings
    float C[NPARAM];     // settings the filters were last designed for
    bool fresh = true;

    Biquad rumble, hum, bass, bands[8], clarity, hiss;
    // click repair (port of the web worklet)
    struct DC { std::vector<float> buf; std::vector<uint8_t> bad; float m = 1e-4f, x1 = 0, x2 = 0; int hold = 0; } dc[2];
    int64_t dcW = 0;
    // tube
    Biquad tDc, tLo, tHi; double tK = -1, tNorm = 1, tOff = 0, tGain = 1, tDrive = -1; float tPrev[2] = {0, 0};
    // bass enhancer
    Biquad bxLp, bxHp, bxTop;
    // dialogue / width
    Biquad ms0, ms1, ms2;
    // tape NR: treble split, envelope, learned hiss floor, smoothed gain
    Biquad nrHp, nrHp2, nrLp, nrLp2; double nrEnv = 0, nrFloor = 1e-3, nrG = 1, nrBlk = 0; int nrN = 0;
    double nrAtt = 0, nrRel = 0, nrOpen = 0, nrClose = 0, nrRise = 1;
    // B-decode: level-dependent treble cut
    Biquad bdHp, bdHp2, bdLp, bdLp2; double bdEnv = 0, bdG = 1, bdAtt = 0, bdRel = 0, bdSm = 0;
    // clarity HD: three bands, lows and mids slightly later than highs, dynamic high lift
    Biquad bbLo, bbHi; Delay bbDL[2], bbDM[2]; double bbFast = 0, bbSlow = 0, bbFa = 0, bbFr = 0, bbSa = 0, bbSr = 0;
    // wide 3D: side widening above the bass, a little space, bass lift
    Biquad wHpS, wAmbHp, wAmbLp, wBass; Delay wDel; int wAmbD = 600;
    // virtualizer
    Delay vxL, vxR, vxSide; Biquad vxShadow, vxRear; Comb cbL[4], cbR[4]; Allpass apL[2], apR[2];
    int itd = 13, sideD = 720;
    // leveler
    double levEnv = 0, levAtt = 0, levRel = 0;
    // limiter (port of the web worklet)
    int L = 240; static constexpr int LN = 8192;
    std::vector<float> lb0, lb1, req; std::vector<double> dq; uint64_t lh = 0, lt = 0, lw = 0; double lg = 1, lRel = 0, lAtt = 0;
    // analysis
    std::vector<float> an, win; int anPos = 0; std::vector<float> re, im;
    std::mutex resM; float res[13];

    void configure(int rate, int ch) {
        sr = rate > 0 ? rate : 48000; inCh = ch > 0 ? ch : 2; fresh = true;
        for (auto &d : dc) { d.buf.assign(4096, 0.f); d.bad.assign(4096, 0); d.m = 1e-4f; d.x1 = d.x2 = 0; d.hold = 0; }
        dcW = 0;
        itd = (int)std::lround(0.00027 * sr); sideD = (int)std::lround(0.015 * sr);
        vxL.init(itd + 4); vxR.init(itd + 4); vxSide.init(sideD + 4);
        vxShadow.lowpass(sr, 2200, 0.707); vxRear.lowpass(sr, 6500, 0.707);
        const int cl[4] = {1116, 1188, 1277, 1356}, al[2] = {556, 441};
        double sc = sr / 44100.0;
        for (int i = 0; i < 4; i++) { cbL[i].init((int)(cl[i] * sc)); cbR[i].init((int)((cl[i] + 23) * sc)); }
        for (int i = 0; i < 2; i++) { apL[i].init((int)(al[i] * sc)); apR[i].init((int)((al[i] + 23) * sc)); }
        levAtt = std::exp(-1.0 / (0.008 * sr)); levRel = std::exp(-1.0 / (0.3 * sr)); levEnv = 0;
        L = std::max(16, (int)std::lround(sr * 0.005));
        lb0.assign(LN, 0.f); lb1.assign(LN, 0.f); req.assign(LN, 1.f); dq.assign(LN, 0.0); lh = lt = lw = 0; lg = 1;
        lRel = std::exp(-1.0 / (0.08 * sr)); lAtt = std::exp(-4.0 / L);
        an.assign(FFTN, 0.f); anPos = 0; re.assign(FFTN, 0.f); im.assign(FFTN, 0.f);
        win.resize(FFTN);
        for (int i = 0; i < FFTN; i++) win[i] = 0.5f - 0.5f * std::cos(2 * M_PI * i / (FFTN - 1));
        tK = -1; tDrive = -1;
        bxLp.lowpass(sr, 120, 0.707); bxHp.highpass(sr, 90, 0.707); bxTop.lowpass(sr, 420, 0.707);
        tDc.highpass(sr, 12, 0.707);
        auto tc = [&](double sec) { return 1 - std::exp(-1.0 / (sec * sr)); };
        // Linkwitz-Riley crossovers: low + high add back to the original sound exactly
        nrHp.highpass(sr, 3500, -3.01); nrHp2 = nrHp; nrLp.lowpass(sr, 3500, -3.01); nrLp2 = nrLp;
        nrAtt = tc(0.005); nrRel = tc(0.10); nrOpen = tc(0.003); nrClose = tc(0.06);
        nrRise = std::pow(10.0, 3.0 / 20.0 * 64.0 / (2.0 * sr));   // the learned floor creeps up 3 dB per 2 s
        bdHp.highpass(sr, 1500, -3.01); bdHp2 = bdHp; bdLp.lowpass(sr, 1500, -3.01); bdLp2 = bdLp; bdAtt = tc(0.002); bdRel = tc(0.05); bdSm = tc(0.004);
        bbLo.lowpass(sr, 150, -3.01); bbHi.highpass(sr, 2400, -3.01);
        for (int c = 0; c < 2; c++) { bbDL[c].init((int)(0.0025 * sr) + 4); bbDM[c].init((int)(0.0008 * sr) + 4); }
        bbFa = tc(0.001); bbFr = tc(0.02); bbSa = tc(0.03); bbSr = tc(0.3);
        wHpS.highpass(sr, 180, -3.01); wAmbHp.highpass(sr, 300, -3.01); wAmbLp.lowpass(sr, 7000, -3.01);
        wAmbD = (int)std::lround(0.013 * sr); wDel.init(wAmbD + 4);
        resetState();
    }
    void resetState() {
        Biquad *all[] = {&rumble, &hum, &bass, &clarity, &hiss, &tDc, &tLo, &tHi, &bxLp, &bxHp, &bxTop, &ms0, &ms1, &ms2, &vxShadow, &vxRear,
                         &nrHp, &nrHp2, &nrLp, &nrLp2, &bdHp, &bdHp2, &bdLp, &bdLp2, &bbLo, &bbHi, &wHpS, &wAmbHp, &wAmbLp, &wBass};
        for (auto *b : all) b->reset();
        for (auto &b : bands) b.reset();
        for (auto &d : dc) { std::fill(d.buf.begin(), d.buf.end(), 0.f); std::fill(d.bad.begin(), d.bad.end(), 0); d.x1 = d.x2 = 0; d.hold = 0; }
        vxL.clear(); vxR.clear(); vxSide.clear();
        for (auto &c : cbL) { std::fill(c.b.begin(), c.b.end(), 0.f); c.store = 0; }
        for (auto &c : cbR) { std::fill(c.b.begin(), c.b.end(), 0.f); c.store = 0; }
        for (auto &a : apL) std::fill(a.b.begin(), a.b.end(), 0.f);
        for (auto &a : apR) std::fill(a.b.begin(), a.b.end(), 0.f);
        std::fill(lb0.begin(), lb0.end(), 0.f); std::fill(lb1.begin(), lb1.end(), 0.f); std::fill(req.begin(), req.end(), 1.f);
        lh = lt = lw = 0; lg = 1; levEnv = 0; tPrev[0] = tPrev[1] = 0; anPos = 0;
        nrEnv = 0; nrFloor = 1e-3; nrG = 1; nrBlk = 0; nrN = 0; bdEnv = 0; bdG = 1; bbFast = bbSlow = 0;
        for (int c = 0; c < 2; c++) { if (!bbDL[c].b.empty()) bbDL[c].clear(); if (!bbDM[c].b.empty()) bbDM[c].clear(); }
        if (!wDel.b.empty()) wDel.clear();
    }

    void design() {
        rumble.highpass(sr, 20 + S[14] * 1.25, 0.707);
        hum.peaking(sr, 50, 5, -0.24 * S[13]);
        bass.lowshelf(sr, 100, S[8]);
        static const double F[8] = {60, 150, 400, 1000, 2400, 6000, 10000, 16000};
        for (int i = 0; i < 8; i++) bands[i].peaking(sr, F[i], 0.9, S[i]);
        clarity.peaking(sr, 8000, 1, S[9]);
        hiss.highshelf(sr, 8000, -0.15 * S[11]);
        double w = (S[16] - 50) / 50.0, dr = S[15];
        tLo.lowshelf(sr, 180, w * 4 + dr / 100 * 1.5);
        tHi.highshelf(sr, 6500, -w * 3 - dr / 100 * 2);
        double t = S[17] / 100.0;
        wBass.lowshelf(sr, 90, S[26] / 100.0 * 4.0);
        ms0.peaking(sr, 300, 0.9, -t * 3); ms1.peaking(sr, 2600, 0.8, t * 7); ms2.peaking(sr, 5200, 1.1, t * 3);
        double k = std::round((1 + dr / 100 * 4) * 20) / 20;
        if (k != tK || dr != tDrive) {
            tK = k; tDrive = dr;
            double b = 0.18;
            tOff = std::tanh(b); tNorm = std::tanh(k + b) - tOff;
            double slope = k * (1 - tOff * tOff);
            tGain = tNorm / slope * dbToGain(dr / 100 * 2.4);
        }
        std::memcpy(C, S, sizeof(S));
    }

    inline float tubeShape(float x) const {
        x = clampf(x, -1.f, 1.f);
        return (float)((std::tanh(tK * x + 0.18) - tOff) / tNorm);
    }
    static inline float bassShape(float x) {
        x = clampf(x, -1.f, 1.f);
        return std::tanh(3 * x) * 0.6f + std::fabs(x) * 0.5f;
    }

    static inline size_t mi(int64_t i) { return (size_t)((uint64_t)i & 4095u); }
    void declickRepair(DC &d, int64_t start, int64_t limit) {
        int64_t e = start;
        while (e < limit - 1 && d.bad[mi(e)] && e - start < 64) e++;
        if (d.bad[mi(e)]) { for (int64_t j = start; j <= e; j++) d.bad[mi(j)] = 0; return; }
        float a = d.buf[mi(start - 1)], b = d.buf[mi(e)];
        int64_t len = e - start + 1;
        for (int64_t j = start; j < e; j++) { d.buf[mi(j)] = a + (b - a) * ((float)(j - start + 1) / len); d.bad[mi(j)] = 0; }
    }

    // ---- analysis for the auto enhancer (same measurements the web version made)
    void fft() {
        int n = FFTN;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * M_PI / len;
            float wr = (float)std::cos(ang), wi = (float)std::sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j, b = i + j + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                    float t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }
    void analyseBlock() {
        double r = 0;
        for (int i = 0; i < FFTN; i++) r += (double)an[i] * an[i];
        r = std::sqrt(r / FFTN);
        if (r <= 2e-4) return;
        for (int i = 0; i < FFTN; i++) { re[i] = an[i] * win[i]; im[i] = 0; }
        fft();
        const int bins = FFTN / 2;
        double hz = sr / FFTN, nn = (double)FFTN * FFTN;
        auto pw = [&](int i) { i = std::max(1, std::min(bins - 1, i)); return ((double)re[i] * re[i] + (double)im[i] * im[i]) / nn; };
        static const double E[8][2] = {{35, 95}, {95, 250}, {250, 650}, {650, 1600}, {1600, 3800}, {3800, 8000}, {8000, 13000}, {13000, 19000}};
        double acc[8];
        for (int b = 0; b < 8; b++) {
            double s = 0;
            int i0 = std::max(1, (int)std::floor(E[b][0] / hz)), i1 = std::min(bins - 1, (int)std::ceil(E[b][1] / hz));
            for (int k = i0; k <= i1; k++) s += pw(k);
            acc[b] = s;
        }
        double humOn = pw((int)std::lround(50 / hz)) + pw((int)std::lround(100 / hz));
        double humOff = (pw((int)std::lround(38 / hz)) + pw((int)std::lround(70 / hz)) + pw((int)std::lround(85 / hz)) + pw((int)std::lround(125 / hz))) / 2;
        double sub = 0;
        for (int q = 1; q <= (int)std::lround(30 / hz); q++) sub += pw(q);
        if (resM.try_lock()) {
            res[0] += 1; res[1] += (float)r; res[2] += (float)humOn; res[3] += (float)humOff; res[4] += (float)sub;
            for (int b = 0; b < 8; b++) res[5 + b] += (float)acc[b];
            resM.unlock();
        }
    }

    void process(const uint8_t *in, int frames, bool isFloat, int16_t *out) {
        float P[NPARAM];
        for (int i = 0; i < NPARAM; i++) P[i] = gParam[i].load(std::memory_order_relaxed);
        const bool neutral = P[22] > 0.5f, limOn = P[21] > 0.5f;
        const bool analysing = gAnalyse.load(std::memory_order_relaxed) != 0;
        const int16_t *i16 = reinterpret_cast<const int16_t *>(in);
        const float *f32 = reinterpret_cast<const float *>(in);

        if (neutral && inCh == 2 && !isFloat) {
            std::memcpy(out, in, (size_t)frames * 4);   // untouched
            if (analysing) {
                for (int f = 0; f < frames; f++) {
                    an[anPos++] = (i16[2 * f] + i16[2 * f + 1]) * (0.5f / 32768.f);
                    if (anPos >= FFTN) { analyseBlock(); anPos = 0; }
                }
            }
            return;
        }
        if (fresh) { std::memcpy(S, P, sizeof(S)); design(); fresh = false; }
        const float smoothK = (float)(1 - std::exp(-64.0 / (0.025 * sr)));
        float Lb[64], Rb[64];
        for (int f0 = 0; f0 < frames; f0 += 64) {
            const int n = std::min(64, frames - f0);
            // read input as stereo float
            for (int j = 0; j < n; j++) {
                const int fi = f0 + j;
                auto smp = [&](int c) -> float { int idx = fi * inCh + c; return isFloat ? f32[idx] : i16[idx] * (1.f / 32768.f); };
                float l, r;
                if (inCh == 1) { l = r = smp(0); }
                else if (inCh == 2) { l = smp(0); r = smp(1); }
                else if (inCh >= 6) {
                    float c = smp(2) * 0.7071f;
                    l = (smp(0) + c + smp(4) * 0.7071f) * 0.6f; r = (smp(1) + c + smp(5) * 0.7071f) * 0.6f;
                } else { float c = smp(2) * 0.7071f; l = (smp(0) + c) * 0.75f; r = (smp(1) + c) * 0.75f; }
                Lb[j] = l; Rb[j] = r;
                if (analysing) { an[anPos++] = (l + r) * 0.5f; if (anPos >= FFTN) { analyseBlock(); anPos = 0; } }
            }
            if (!neutral) {
                bool changed = false;
                for (int i = 0; i < NPARAM; i++) {
                    if (i == 21 || i == 22) continue;
                    S[i] += (P[i] - S[i]) * smoothK;
                    if (std::fabs(S[i] - P[i]) < 1e-3f) S[i] = P[i];
                    if (std::fabs(S[i] - C[i]) > 1e-3f) changed = true;
                }
                if (changed) design();
                processBlock(Lb, Rb, n, limOn);
            }
            for (int j = 0; j < n; j++) {
                out[2 * (f0 + j)] = (int16_t)std::lrint(clampf(Lb[j], -1.f, 1.f) * 32767.f);
                out[2 * (f0 + j) + 1] = (int16_t)std::lrint(clampf(Rb[j], -1.f, 1.f) * 32767.f);
            }
        }
    }

    void processBlock(float *Lb, float *Rb, int n, bool limOn) {
        float *ch[2] = {Lb, Rb};
        // tone filters
        for (int c = 0; c < 2; c++) {
            float *x = ch[c];
            for (int j = 0; j < n; j++) {
                float v = rumble.run(x[j], c);
                v = hum.run(v, c); v = bass.run(v, c);
                for (auto &b : bands) v = b.run(v, c);
                v = clarity.run(v, c); v = hiss.run(v, c);
                x[j] = v;
            }
        }
        // click & pop repair
        const float click = S[12];
        if (P12on(click)) {
            const float k = 14 - 0.09f * click;
            const float a = (float)std::exp(-1.0 / (0.01 * sr));
            const int64_t D = 128, w0 = dcW;
            for (int c = 0; c < 2; c++) {
                DC &d = dc[c];
                int64_t w = w0;
                float *x = ch[c];
                for (int j = 0; j < n; j++) {
                    float v = x[j];
                    d.buf[mi(w)] = v; d.bad[mi(w)] = 0;
                    float e = std::fabs(v - 2 * d.x1 + d.x2), T = k * d.m + 1e-4f;
                    if (e > T) { d.bad[mi(w - 2)] = 1; d.bad[mi(w - 1)] = 1; d.bad[mi(w)] = 1; d.hold = 6; }
                    else if (d.hold > 0) { d.bad[mi(w)] = 1; d.hold--; }
                    d.m = a * d.m + (1 - a) * std::min(e, T);
                    d.x2 = d.x1; d.x1 = v;
                    size_t r = mi(w - D);
                    if (d.bad[r]) declickRepair(d, w - D, w);
                    x[j] = d.buf[r];
                    w++;
                }
            }
            dcW = w0 + n;
        }
        // tape NR (single-ended, DNR-style): the treble above ~3.5 kHz is turned down only while it is
        // close to the hiss level learned from the quietest moments; louder treble passes untouched.
        if (S[23] > 0.01f) {
            const double a = S[23] / 100.0;
            const double gmin = dbToGain(-20.0 * a), margin = dbToGain(6.0 + 6.0 * a), ratio = 1.0 + a;
            for (int j = 0; j < n; j++) {
                float hl = nrHp2.run(nrHp.run(Lb[j], 0), 0), hr = nrHp2.run(nrHp.run(Rb[j], 1), 1);
                float ll = nrLp2.run(nrLp.run(Lb[j], 0), 0), lr = nrLp2.run(nrLp.run(Rb[j], 1), 1);
                double e = std::max(std::fabs(hl), std::fabs(hr));
                nrEnv += (e - nrEnv) * (e > nrEnv ? nrAtt : nrRel);
                nrBlk += nrEnv;
                if (++nrN >= 64) {   // learn the hiss floor: follows the quietest level, creeps up slowly
                    double m = nrBlk / nrN; nrBlk = 0; nrN = 0;
                    if (m < nrFloor) nrFloor = nrFloor * 0.8 + m * 0.2; else nrFloor *= nrRise;
                    nrFloor = std::min(0.05, std::max(3e-5, nrFloor));
                }
                double r = nrEnv / (nrFloor * margin);
                double tg = r >= 1 ? 1.0 : std::max(gmin, std::pow(r, ratio));
                nrG += (tg - nrG) * (tg > nrG ? nrOpen : nrClose);
                Lb[j] = ll + (float)nrG * hl; Rb[j] = lr + (float)nrG * hr;
            }
        }
        // B-decode (Dolby-B-style playback): quiet treble is cut by up to 10 dB, loud treble is not cut
        if (S[24] > 0.01f) {
            const double maxCut = 10.0 * S[24] / 100.0;
            for (int j = 0; j < n; j++) {
                float hl = bdHp2.run(bdHp.run(Lb[j], 0), 0), hr = bdHp2.run(bdHp.run(Rb[j], 1), 1);
                float ll = bdLp2.run(bdLp.run(Lb[j], 0), 0), lr = bdLp2.run(bdLp.run(Rb[j], 1), 1);
                double e = std::max(std::fabs(hl), std::fabs(hr));
                bdEnv += (e - bdEnv) * (e > bdEnv ? bdAtt : bdRel);
                double ld = 20.0 * std::log10(bdEnv + 1e-9);
                double cut = maxCut * clampf((float)((-15.0 - ld) / 30.0), 0.f, 1.f);
                bdG += (dbToGain(-cut) - bdG) * bdSm;
                Lb[j] = ll + (float)bdG * hl; Rb[j] = lr + (float)bdG * hr;
            }
        }
        // tube amplifier (2x oversampled shaping)
        if (S[15] > 0.01f) {
            for (int c = 0; c < 2; c++) {
                float *x = ch[c];
                for (int j = 0; j < n; j++) {
                    float v = x[j], mid = 0.5f * (v + tPrev[c]);
                    tPrev[c] = v;
                    float y = 0.5f * (tubeShape(mid) + tubeShape(v));
                    y = tDc.run(y, c); y = tLo.run(y, c); y = tHi.run(y, c);
                    x[j] = (float)(y * tGain);
                }
            }
        }
        // bass enhancer
        if (S[19] > 0.01f) {
            const float g = S[19] / 100.f * 0.9f;
            for (int c = 0; c < 2; c++) {
                float *x = ch[c];
                for (int j = 0; j < n; j++) {
                    float h = bxLp.run(x[j], c);
                    h = bassShape(h); h = bxHp.run(h, c); h = bxTop.run(h, c);
                    x[j] += h * g;
                }
            }
        }
        // clarity HD (BBE-style): highs first, mids ~0.5 ms and lows ~2 ms later, plus a high lift that grows on attacks
        if (S[25] > 0.01f) {
            const double a = S[25] / 100.0;
            const int dl = (int)std::lround(a * 0.0020 * sr), dm = (int)std::lround(a * 0.0005 * sr);
            const double gLo = dbToGain(a * 2.5), trim = dbToGain(-a * 1.5);
            for (int j = 0; j < n; j++) {
                float lo[2], hi[2], md[2], in2[2] = {Lb[j], Rb[j]};
                for (int c = 0; c < 2; c++) {
                    lo[c] = bbLo.run(in2[c], c); hi[c] = bbHi.run(in2[c], c); md[c] = in2[c] - lo[c] - hi[c];
                    bbDL[c].push(lo[c]); bbDM[c].push(md[c]);
                }
                double e = std::max(std::fabs(hi[0]), std::fabs(hi[1]));
                bbFast += (e - bbFast) * (e > bbFast ? bbFa : bbFr);
                bbSlow += (e - bbSlow) * (e > bbSlow ? bbSa : bbSr);
                double tr = std::min(1.0, std::max(0.0, bbFast / (bbSlow + 1e-6) - 1.0));
                double gHi = std::exp((a * 3.5 + a * 3.0 * tr) * 0.11512925);
                for (int c = 0; c < 2; c++) {
                    float y = (float)(bbDL[c].tap(dl) * gLo + bbDM[c].tap(dm) + hi[c] * gHi);
                    (c ? Rb : Lb)[j] = (float)(y * trim);
                }
            }
        }
        // dialogue enhancer and stereo width (mid/side)
        if (S[17] > 0.01f || std::fabs(S[18] - 100) > 0.01f) {
            const float t = S[17] / 100.f, sg = (S[18] / 100.f) * (1 - t * 0.35f);
            for (int j = 0; j < n; j++) {
                float m = 0.5f * (Lb[j] + Rb[j]), s = 0.5f * (Lb[j] - Rb[j]);
                m = ms0.run(m, 0); m = ms1.run(m, 0); m = ms2.run(m, 0);
                s *= sg;
                Lb[j] = m + s; Rb[j] = m - s;
            }
        }
        // wide 3D (ViVA-style): wider stereo above the bass, a little space (also spreads mono tapes), bass lift
        if (S[26] > 0.01f) {
            const float w = S[26] / 100.f, trim = (float)dbToGain(-1.0 * w);
            for (int j = 0; j < n; j++) {
                float m = 0.5f * (Lb[j] + Rb[j]), sd = 0.5f * (Lb[j] - Rb[j]);
                wDel.push(m);
                float amb = wAmbLp.run(wAmbHp.run(wDel.tap(wAmbD), 0), 0);
                float sw = sd + wHpS.run(sd, 0) * 1.6f * w + amb * 0.35f * w;
                float mb = wBass.run(m, 0);
                Lb[j] = (mb + sw) * trim; Rb[j] = (mb - sw) * trim;
            }
        }
        // virtualizer: speakers at the front, delayed sides behind, a small room
        if (S[10] > 0.01f) {
            const float w = S[10] / 100.f, dry = 1 - w, wet = w * 0.7f;
            for (int j = 0; j < n; j++) {
                float l = Lb[j], r = Rb[j];
                vxL.push(l); vxR.push(r);
                float fl = l + 0.6f * vxShadow.run(vxR.tap(itd), 0);
                float fr = r + 0.6f * vxShadow.run(vxL.tap(itd), 1);
                float side = 0.5f * (l - r);
                vxSide.push(side);
                float rs = vxRear.run(vxSide.tap(sideD), 0) * 0.7f * 0.46f;
                float send = (l + r) * 0.5f * 0.25f * 0.6f, ol = 0, orr = 0;
                for (int i = 0; i < 4; i++) { ol += cbL[i].run(send, 0.78f, 0.3f); orr += cbR[i].run(send, 0.78f, 0.3f); }
                for (int i = 0; i < 2; i++) { ol = apL[i].run(ol); orr = apR[i].run(orr); }
                float wl = fl * 1.05f + rs * 1.5f + ol * 1.1f, wr = fr * 1.05f - rs * 1.5f + orr * 1.1f;
                Lb[j] = l * dry + wl * wet; Rb[j] = r * dry + wr * wet;
            }
        }
        // volume leveler
        if (S[20] > 0.01f) {
            const double thr = -8 - S[20] * 0.24, ratio = 1.5 + S[20] * 0.045, knee = 10;
            auto curve = [&](double over) { if (over <= -knee / 2) return 0.0; if (over >= knee / 2) return over * (1 / ratio - 1); return (1 / ratio - 1) * (over + knee / 2) * (over + knee / 2) / (2 * knee); };
            const double makeup = S[20] / 100.0 * 8 - 0.6 * curve(-thr);
            for (int j = 0; j < n; j++) {
                double pk = std::max(std::fabs(Lb[j]), std::fabs(Rb[j]));
                levEnv = pk > levEnv ? levAtt * levEnv + (1 - levAtt) * pk : levRel * levEnv + (1 - levRel) * pk;
                double gr = curve(20 * std::log10(levEnv + 1e-9) - thr);
                float g = (float)dbToGain(gr + makeup);
                Lb[j] *= g; Rb[j] *= g;
            }
        }
        // look-ahead limiter at -1 dB
        if (limOn) {
            const uint64_t mask = LN - 1;
            const double ceil = std::pow(10.0, -1.0 / 20);
            for (int j = 0; j < n; j++) {
                uint64_t w = lw;
                double pk = std::max(std::fabs(Lb[j]), std::fabs(Rb[j])), need = pk > ceil ? ceil / pk : 1;
                lb0[w & mask] = Lb[j]; lb1[w & mask] = Rb[j]; req[w & mask] = (float)need;
                while (lt > lh && req[((uint64_t)dq[(lt - 1) & mask]) & mask] >= need) lt--;
                dq[lt & mask] = (double)w; lt++;
                while ((uint64_t)dq[lh & mask] + L < w) lh++;
                double target = req[((uint64_t)dq[lh & mask]) & mask];
                lg = target < lg ? target + (lg - target) * lAtt : target + (lg - target) * lRel;
                uint64_t ro = (w - L) & mask;
                double yl = lb0[ro] * lg, yr = lb1[ro] * lg;
                Lb[j] = (float)std::max(-ceil, std::min(ceil, yl));
                Rb[j] = (float)std::max(-ceil, std::min(ceil, yr));
                lw++;
            }
        }
    }
    static inline bool P12on(float click) { return click > 0.01f; }
};

Engine *gE = nullptr;
std::mutex gM;

struct Init {
    Init() {
        for (auto &p : gParam) p.store(0.f);
        gParam[16].store(50.f); gParam[18].store(100.f); gParam[21].store(1.f); gParam[22].store(1.f);
    }
} gInit;

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_mymusic_cutter_FxNative_configure(JNIEnv *, jclass, jint sr, jint ch) {
    std::lock_guard<std::mutex> lk(gM);
    if (!gE) gE = new Engine();
    gE->configure(sr, ch);
}

JNIEXPORT void JNICALL Java_com_mymusic_cutter_FxNative_reset(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lk(gM);
    if (gE) gE->resetState();
}

JNIEXPORT void JNICALL Java_com_mymusic_cutter_FxNative_setParams(JNIEnv *env, jclass, jfloatArray arr) {
    jsize n = env->GetArrayLength(arr);
    float tmp[NPARAM];
    env->GetFloatArrayRegion(arr, 0, std::min<jsize>(n, NPARAM), tmp);
    for (int i = 0; i < std::min<int>(n, NPARAM); i++) gParam[i].store(tmp[i], std::memory_order_relaxed);
}

JNIEXPORT void JNICALL Java_com_mymusic_cutter_FxNative_process(JNIEnv *env, jclass, jobject in, jint offset, jint frames,
                                                                jboolean isFloat, jobject out) {
    auto *ip = static_cast<uint8_t *>(env->GetDirectBufferAddress(in));
    auto *op = static_cast<int16_t *>(env->GetDirectBufferAddress(out));
    if (!ip || !op || frames <= 0) return;
    static thread_local bool ftz = false;
    if (!ftz) { enableFlushToZero(); ftz = true; }
    std::lock_guard<std::mutex> lk(gM);
    if (!gE) { gE = new Engine(); gE->configure(48000, 2); }
    gE->process(ip + offset, frames, isFloat == JNI_TRUE, op);
}

JNIEXPORT void JNICALL Java_com_mymusic_cutter_FxNative_analyse(JNIEnv *, jclass, jboolean on) {
    if (on) {
        std::lock_guard<std::mutex> lk(gM);
        if (gE) { std::lock_guard<std::mutex> r(gE->resM); std::fill(gE->res, gE->res + 13, 0.f); gE->anPos = 0; }
    }
    gAnalyse.store(on ? 1 : 0);
}

JNIEXPORT jfloatArray JNICALL Java_com_mymusic_cutter_FxNative_analysisResult(JNIEnv *env, jclass) {
    float out[13] = {0};
    if (gE) { std::lock_guard<std::mutex> r(gE->resM); std::memcpy(out, gE->res, sizeof(out)); }
    jfloatArray a = env->NewFloatArray(13);
    env->SetFloatArrayRegion(a, 0, 13, out);
    return a;
}

}  // extern "C"
