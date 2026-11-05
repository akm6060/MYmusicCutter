// MYmusic Cutter sound engine (C++).
// Runs inside the native player's audio pipeline: every decoded block passes through here
// before it reaches the speaker or headphones. Nothing travels back and forth through the
// web page, so there is no feedback delay. The chain is the one from version 2.1:
// rumble > hum > bass > 8 bands > clarity > hiss > [click repair] > [tube amp] > [bass enhancer]
//   > [tape NR] > [B-decode] > [tube amp] > [bass enhancer] > [Mach3 bass] > [MP3 enhance] > [clarity HD]
//   > [dialogue + stereo width] > [wide 3D] > [virtualizer] > [volume leveler] > [limiter]
// Stages added from 4.8 on are skipped entirely while their slider is at 0.
// (tube amp and wide 3D are no longer offered by the app: their settings always arrive as 0)
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

constexpr int NPARAM = 56;
// 0..7 bands, 8 bass, 9 clarity, 10 virtual, 11 hiss, 12 click, 13 hum, 14 rumble, 15 tube,
// 16 warmth, 17 dialog, 18 width, 19 bassx, 20 level, 21 limiter on, 22 neutral,
// 23 tape NR, 24 B-decode, 25 clarity HD, 26 wide 3D, 27 MP3 enhance, 28 Mach3 bass,
// 29 equal-volume gain (dB), 30 headphone crossfeed, 31 exciter,
// 32 preamp (dB), 33 equalizer layout (8 / 10 / 20 bands), 34 bass-boost frequency (Hz, 0 = 100),
// 35..54 band gains of the 10- and 20-band layouts
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
    Biquad eqx[20]; int eqMode = 8, eqN = 0;   // 10- or 20-band layout (the 8-band one uses bands[])
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
    // clarity HD (reworked in 4.9): treble band only, lows slightly later, smooth treble lift
    Biquad bbDet, bbShelf, bbLow; Delay bbDL[2]; double bbEnv = 0, bbSlow = 0, bbA = 0, bbR = 0, bbSa = 0, bbSr = 0, bbG = 0, bbGs = 0, bbDesigned = -99;
    // MP3 enhance: rebuilds the treble a low-bit-rate encoder cut off above fc
    Biquad hfSrcHp1, hfSrcHp2, hfSrcLp1, hfSrcLp2, hfGenHp1, hfGenHp2, hfGenLp, hfDc;
    double hfFc = 0, hfEnvS[2] = {0, 0}, hfEnvG[2] = {0, 0}, hfK[2] = {0, 0}, hfEa = 0, hfKs = 0;
    static constexpr int HFN = 2048;
    std::vector<float> hfBuf, hfRe, hfIm, hfWin; std::vector<double> hfAvg; int hfPos = 0, hfBlocks = 0;
    // Mach3 bass: low band split off, deep boost, gently compressed so it stays tight
    Biquad m3Lp1, m3Lp2, m3Hp1, m3Hp2, m3Sub; double m3Env = 0, m3G = 1, m3A = 0, m3R = 0;
    // headphone crossfeed: low/mid part of each side, slightly later, into the other ear
    Biquad xfLp; Delay xfD[2]; int xfDelay = 13;
    // exciter: treble band gently saturated, only the new harmonics mixed back in
    Biquad exHp, exHp2, exLp, exOut, exOut2;
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
        bbDet.highpass(sr, 4500, -3.01); bbDesigned = -99;
        for (int c = 0; c < 2; c++) bbDL[c].init(8);
        bbA = tc(0.005); bbR = tc(0.08); bbSa = tc(0.05); bbSr = tc(0.4); bbGs = tc(0.01);
        hfBuf.assign(HFN, 0.f); hfRe.assign(HFN, 0.f); hfIm.assign(HFN, 0.f); hfWin.resize(HFN); hfAvg.assign(HFN / 2, 0.0);
        for (int i = 0; i < HFN; i++) hfWin[i] = 0.5f - 0.5f * std::cos(2 * M_PI * i / (HFN - 1));
        hfPos = 0; hfBlocks = 0; hfFc = 0; hfEa = tc(0.01); hfKs = tc(0.02);
        hfDc.highpass(sr, 400, -3.01);
        m3Lp1.lowpass(sr, 120, -3.01); m3Lp2 = m3Lp1; m3Hp1.highpass(sr, 120, -3.01); m3Hp2 = m3Hp1; m3Sub.highpass(sr, 28, -3.01);
        m3A = tc(0.01); m3R = tc(0.15);
        xfLp.lowpass(sr, 700, -3.01); xfDelay = (int)std::lround(0.0003 * sr);
        for (int c = 0; c < 2; c++) xfD[c].init(xfDelay + 4);
        exHp.highpass(sr, 2500, -3.01); exHp2 = exHp; exLp.lowpass(sr, 7000, -3.01); exOut.highpass(sr, 5000, -3.01); exOut2 = exOut;
        wHpS.highpass(sr, 180, -3.01); wAmbHp.highpass(sr, 300, -3.01); wAmbLp.lowpass(sr, 7000, -3.01);
        wAmbD = (int)std::lround(0.013 * sr); wDel.init(wAmbD + 4);
        resetState();
    }
    void resetState() {
        Biquad *all[] = {&rumble, &hum, &bass, &clarity, &hiss, &tDc, &tLo, &tHi, &bxLp, &bxHp, &bxTop, &ms0, &ms1, &ms2, &vxShadow, &vxRear,
                         &nrHp, &nrHp2, &nrLp, &nrLp2, &bdHp, &bdHp2, &bdLp, &bdLp2, &bbDet, &bbShelf, &bbLow, &wHpS, &wAmbHp, &wAmbLp, &wBass,
                         &hfSrcHp1, &hfSrcHp2, &hfSrcLp1, &hfSrcLp2, &hfGenHp1, &hfGenHp2, &hfGenLp, &hfDc,
                         &m3Lp1, &m3Lp2, &m3Hp1, &m3Hp2, &m3Sub, &xfLp, &exHp, &exHp2, &exLp, &exOut, &exOut2};
        for (auto *b : all) b->reset();
        for (auto &b : bands) b.reset();
        for (auto &b : eqx) b.reset();
        for (auto &d : dc) { std::fill(d.buf.begin(), d.buf.end(), 0.f); std::fill(d.bad.begin(), d.bad.end(), 0); d.x1 = d.x2 = 0; d.hold = 0; }
        vxL.clear(); vxR.clear(); vxSide.clear();
        for (auto &c : cbL) { std::fill(c.b.begin(), c.b.end(), 0.f); c.store = 0; }
        for (auto &c : cbR) { std::fill(c.b.begin(), c.b.end(), 0.f); c.store = 0; }
        for (auto &a : apL) std::fill(a.b.begin(), a.b.end(), 0.f);
        for (auto &a : apR) std::fill(a.b.begin(), a.b.end(), 0.f);
        std::fill(lb0.begin(), lb0.end(), 0.f); std::fill(lb1.begin(), lb1.end(), 0.f); std::fill(req.begin(), req.end(), 1.f);
        lh = lt = lw = 0; lg = 1; levEnv = 0; tPrev[0] = tPrev[1] = 0; anPos = 0;
        nrEnv = 0; nrFloor = 1e-3; nrG = 1; nrBlk = 0; nrN = 0; bdEnv = 0; bdG = 1; bbEnv = bbSlow = 0; bbG = 0; bbDesigned = -99;
        for (int c = 0; c < 2; c++) { if (!bbDL[c].b.empty()) bbDL[c].clear(); hfEnvS[c] = hfEnvG[c] = hfK[c] = 0; }
        m3Env = 0; m3G = 1;
        for (int c = 0; c < 2; c++) if (!xfD[c].b.empty()) xfD[c].clear();
        if (!wDel.b.empty()) wDel.clear();
    }

    void design() {
        rumble.highpass(sr, 20 + S[14] * 1.25, 0.707);
        hum.peaking(sr, 50, 5, -0.24 * S[13]);
        bass.lowshelf(sr, S[34] > 1 ? S[34] : 100, S[8]);
        static const double F[8] = {70, 150, 400, 1000, 2400, 5000, 7000, 10000};
        for (int i = 0; i < 8; i++) bands[i].peaking(sr, F[i], 0.9, S[i]);
        {
            int m = (int)std::lround(S[33]);
            eqMode = (m == 10 || m == 20) ? m : 8;
            static const double F10[10] = {60, 100, 240, 500, 1000, 1800, 3400, 5700, 9000, 13000};
            static const double F20[20] = {40, 60, 80, 100, 120, 140, 160, 180, 200, 220, 300, 500,
                                           1000, 2000, 3000, 4000, 6000, 8000, 10000, 13000};
            if (eqMode == 10) {
                eqN = 10;
                for (int i = 0; i < 10; i++) eqx[i].peaking(sr, F10[i], 1.0, S[35 + i]);       // soft, wide
            } else if (eqMode == 20) {
                eqN = 20;
                for (int i = 0; i < 20; i++) {                                                   // sharp: width set
                    double lo = i > 0 ? F20[i - 1] : F20[0] * 0.67, hi = i < 19 ? F20[i + 1] : F20[19] * 1.4;   // by the
                    double q = F20[i] / ((hi - lo) / 2);                                         // neighbours, so
                    eqx[i].peaking(sr, F20[i], std::max(1.4, std::min(16.0, q * 2.0)), S[35 + i]);      // they barely overlap
                }
            } else eqN = 0;
        }
        clarity.peaking(sr, 8000, 1, S[9]);
        hiss.highshelf(sr, 8000, -0.15 * S[11]);
        double w = (S[16] - 50) / 50.0, dr = S[15];
        tLo.lowshelf(sr, 180, w * 4 + dr / 100 * 1.5);
        tHi.highshelf(sr, 6500, -w * 3 - dr / 100 * 2);
        double t = S[17] / 100.0;
        wBass.lowshelf(sr, 90, S[26] / 100.0 * 4.0);
        bbLow.lowshelf(sr, 120, S[25] / 100.0 * 1.5);
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
        static const double E[8][2] = {{45, 105}, {105, 250}, {250, 650}, {650, 1600}, {1600, 3600}, {3600, 6000}, {6000, 8500}, {8500, 13000}};
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
                if (S[33] != P[33]) { S[33] = P[33]; changed = true; }
                for (int i = 0; i < NPARAM; i++) {
                    if (i == 21 || i == 22 || i == 33) continue;
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

    void hfFft() {
        int n = HFN;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { std::swap(hfRe[i], hfRe[j]); std::swap(hfIm[i], hfIm[j]); }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * M_PI / len;
            float wr = (float)std::cos(ang), wi = (float)std::sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j, b = i + j + len / 2;
                    float xr = hfRe[b] * cr - hfIm[b] * ci, xi = hfRe[b] * ci + hfIm[b] * cr;
                    hfRe[b] = hfRe[a] - xr; hfIm[b] = hfIm[a] - xi; hfRe[a] += xr; hfIm[a] += xi;
                    float t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }
    void hfDesign(double fc) {
        hfFc = fc;
        hfSrcHp1.highpass(sr, fc * 0.5, -3.01); hfSrcHp2 = hfSrcHp1;
        hfSrcLp1.lowpass(sr, fc * 0.97, -3.01); hfSrcLp2 = hfSrcLp1;
        hfGenHp1.highpass(sr, fc, -3.01); hfGenHp2 = hfGenHp1;
        hfGenLp.lowpass(sr, std::min(fc * 1.9, sr * 0.45), -3.01);
        for (int c = 0; c < 2; c++) hfK[c] = 0;
    }
    void hfDetect(const float *Lb, const float *Rb, int n) {
        for (int j = 0; j < n; j++) {
            hfBuf[hfPos++] = 0.5f * (Lb[j] + Rb[j]);
            if (hfPos < HFN) continue;
            hfPos = 0;
            double r = 0;
            for (int i = 0; i < HFN; i++) r += (double)hfBuf[i] * hfBuf[i];
            if (std::sqrt(r / HFN) < 2e-3) continue;          // too quiet to judge
            for (int i = 0; i < HFN; i++) { hfRe[i] = hfBuf[i] * hfWin[i]; hfIm[i] = 0; }
            hfFft();
            for (int k = 0; k < HFN / 2; k++) hfAvg[k] = 0.85 * hfAvg[k] + 0.15 * ((double)hfRe[k] * hfRe[k] + (double)hfIm[k] * hfIm[k]);
            if (++hfBlocks < 8 || (hfBlocks % 4) != 0) continue;
            const double hz = sr / HFN;
            int k4 = (int)(4000 / hz), k8 = (int)(8000 / hz), top = std::min(HFN / 2 - 2, (int)(std::min(20000.0, sr * 0.47) / hz));
            double ref = 0; for (int k = k4; k < k8; k++) ref += hfAvg[k]; ref /= std::max(1, k8 - k4);
            if (ref <= 0) continue;
            int kc = 0;
            for (int k = top; k > k8; k--) {                   // highest point that still carries sound
                if (hfAvg[k] > ref * 1e-5 && hfAvg[k - 1] > ref * 1e-5 && hfAvg[k - 2] > ref * 1e-5) { kc = k; break; }
            }
            double fc = kc * hz;
            double want = (kc == 0) ? 8500 : (fc >= 18500 || fc >= sr * 0.44) ? 0 : std::max(7000.0, fc);
            if (want == 0) { hfFc = 0; continue; }             // full range: nothing to rebuild
            if (hfFc == 0 || std::fabs(want - hfFc) > 600) hfDesign(want);
        }
    }

    void processBlock(float *Lb, float *Rb, int n, bool limOn) {
        float *ch[2] = {Lb, Rb};
        // tone filters
        for (int c = 0; c < 2; c++) {
            float *x = ch[c];
            const bool pre = std::fabs(S[32]) > 0.01f;
            const float preG = pre ? (float)dbToGain(S[32]) : 1.f;
            for (int j = 0; j < n; j++) {
                float v = pre ? x[j] * preG : x[j];
                v = rumble.run(v, c);
                v = hum.run(v, c); v = bass.run(v, c);
                if (eqN == 0) { for (auto &b : bands) v = b.run(v, c); }
                else { for (int b = 0; b < eqN; b++) v = eqx[b].run(v, c); }
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
        // Mach3 bass: the band below 120 Hz (Linkwitz-Riley split, so the rest is untouched) is lifted up to
        // +9 dB, sub-sonic rumble removed, and gently compressed so the bass stays deep and tight, never boomy.
        if (S[28] > 0.01f) {
            const double boost = dbToGain(9.0 * S[28] / 100.0), T = 0.22;
            for (int j = 0; j < n; j++) {
                float lo[2], hi[2], in2[2] = {Lb[j], Rb[j]};
                for (int c = 0; c < 2; c++) {
                    lo[c] = (float)(m3Sub.run(m3Lp2.run(m3Lp1.run(in2[c], c), c), c) * boost);
                    hi[c] = m3Hp2.run(m3Hp1.run(in2[c], c), c);
                }
                double e = std::max(std::fabs(lo[0]), std::fabs(lo[1]));
                m3Env += (e - m3Env) * (e > m3Env ? m3A : m3R);
                double tg = m3Env > T ? std::pow(T / m3Env, 2.0 / 3.0) : 1.0;   // 3:1 above the threshold
                tg = std::max(tg, 1.0 / boost);                                   // never below the original bass
                m3G += (tg - m3G) * (tg < m3G ? m3A : m3R);
                Lb[j] = hi[0] + (float)(lo[0] * m3G); Rb[j] = hi[1] + (float)(lo[1] * m3G);
            }
        }
        // MP3 enhance: finds where a low-bit-rate encoder cut the treble off (about 11 kHz at 64 kbps,
        // 16 kHz at 128 kbps) and rebuilds the missing octave from the band just below it.
        // Full-range recordings are detected and left alone.
        if (S[27] > 0.01f) {
            hfDetect(Lb, Rb, n);
            if (hfFc > 0) {
                const double amt = S[27] / 100.0 * 0.55;
                for (int c = 0; c < 2; c++) {
                    float *x = ch[c];
                    for (int j = 0; j < n; j++) {
                        float sb = hfSrcLp2.run(hfSrcLp1.run(hfSrcHp2.run(hfSrcHp1.run(x[j], c), c), c), c);
                        float g = std::fabs(sb);                                   // one octave up
                        float h = hfGenLp.run(hfGenHp2.run(hfGenHp1.run(g, c), c), c);
                        hfEnvS[c] += (std::fabs(sb) - hfEnvS[c]) * hfEa;
                        hfEnvG[c] += (std::fabs(h) - hfEnvG[c]) * hfEa;
                        double tk = hfEnvG[c] > 1e-7 ? std::min(6.0, amt * hfEnvS[c] / hfEnvG[c]) : 0.0;
                        hfK[c] += (tk - hfK[c]) * hfKs;
                        x[j] += (float)(hfK[c] * h);
                    }
                }
            }
        } else if (hfFc != 0) { hfFc = 0; hfBlocks = 0; std::fill(hfAvg.begin(), hfAvg.end(), 0.0); }
        // clarity HD (BBE-style, reworked): a smooth treble shelf from ~5 kHz, +4 dB at full and up to +2 dB
        // more on note attacks; a slight bass contour. Mid frequencies are not touched; no band splitting.
        if (S[25] > 0.01f) {
            const double a = S[25] / 100.0, base = a * 4.0, extra = a * 2.0;
            for (int j0 = 0; j0 < n; j0 += 16) {
                const int m = std::min(16, n - j0);
                for (int j = j0; j < j0 + m; j++) {
                    double e = std::max(std::fabs(bbDet.run(Lb[j], 0)), std::fabs(bbDet.run(Rb[j], 1)));
                    bbEnv += (e - bbEnv) * (e > bbEnv ? bbA : bbR);
                    bbSlow += (e - bbSlow) * (e > bbSlow ? bbSa : bbSr);
                }
                double tr = std::min(1.0, std::max(0.0, bbEnv / (bbSlow + 1e-6) - 1.0));
                bbG += ((base + extra * tr) - bbG) * (1 - std::pow(1 - bbGs, m));
                if (std::fabs(bbG - bbDesigned) > 0.02) { bbShelf.highshelf(sr, 5000, bbG); bbDesigned = bbG; }
                for (int j = j0; j < j0 + m; j++) {
                    Lb[j] = bbShelf.run(bbLow.run(Lb[j], 0), 0);
                    Rb[j] = bbShelf.run(bbLow.run(Rb[j], 1), 1);
                }
            }
        }
        // exciter (classic aural exciter): overtones one octave above the 2.5-7 kHz presence band are made
        // in proportion to the music (no squashing of the original notes) and mixed in quietly above 5 kHz
        if (S[31] > 0.01f) {
            const float mix = S[31] / 100.f * 0.25f;
            for (int c = 0; c < 2; c++) {
                float *x = ch[c];
                for (int j = 0; j < n; j++) {
                    float b = exLp.run(exHp2.run(exHp.run(x[j], c), c), c);
                    float h = exOut2.run(exOut.run(std::fabs(b), c), c);            // overtones only
                    x[j] += h * mix;
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
        // headphone crossfeed: like listening to speakers - each ear also hears the other side's bass and
        // mids a fraction of a millisecond later; centred (mono) sound stays as it was
        if (S[30] > 0.01f) {
            const float g = S[30] / 100.f * 0.45f;
            for (int j = 0; j < n; j++) {
                float ll = xfLp.run(Lb[j], 0), lr = xfLp.run(Rb[j], 1);
                xfD[0].push(ll); xfD[1].push(lr);
                float l = Lb[j] - g * ll + g * xfD[1].tap(xfDelay);
                float r = Rb[j] - g * lr + g * xfD[0].tap(xfDelay);
                Lb[j] = l; Rb[j] = r;
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
        // equal volume: each song's measured loudness brought to the same level (the limiter catches peaks)
        if (std::fabs(S[29]) > 0.01f) {
            const float g = (float)dbToGain(S[29]);
            for (int j = 0; j < n; j++) { Lb[j] *= g; Rb[j] *= g; }
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
