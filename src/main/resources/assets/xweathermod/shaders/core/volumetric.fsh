#version 330

// XWeather volumetric storm clouds: a ray marcher over analytic storm shapes (storm layer, supercell parts,
// wall cloud, tail cloud, tornado funnel and dust skirt). The shapes mirror the particle clouds
// (CloudSpawner / FunnelSpawner) so both modes look like the same storm.
//
// Output is PREMULTIPLIED alpha, which is what vanilla's transparency post pass expects for the clouds target.

layout(std140) uniform Projection {
    mat4 ProjMat;
};

#define MAX_STORMS 6
#define MAX_TORN 6

layout(std140) uniform XWeatherData {
    mat4 InvView;
    vec4 CamPos;                 // x: camera X (wrapped), y: camera Y, z: camera Z (wrapped), w: time in seconds
    vec4 Cfg;                    // x: max steps, y: max distance, z: 1 if depth is zero-to-one, w: density multiplier
    vec4 Params;                 // x: cloud base altitude, y: storm count, z: tornado count, w: unused
    vec4 LightCol;               // rgb: vanilla cloud colour (time of day and weather), a: unused
    vec4 SA[MAX_STORMS];         // x, z (camera relative), radius, development
    vec4 SB[MAX_STORMS];         // intensity, supercell (1 or 0), forward x, forward z
    vec4 SC[MAX_STORMS];         // wall cloud x, z (camera relative), radius (0 = none), lowest Y (absolute)
    vec4 TA[MAX_TORN];           // x, z (camera relative), size (radius), ground Y (absolute)
    vec4 TB[MAX_TORN];           // top Y (absolute), intensity, wind mph, unused
    vec4 TC[MAX_TORN];           // travel direction x, z, unused, unused
};

#ifdef HAS_DEPTH
uniform sampler2D MainDepthSampler;
#endif

in vec2 texCoord;
in vec3 viewDir;
flat in mat4 invProj;

out vec4 fragColor;

const float PI = 3.14159265;
const vec3 SUN = vec3(0.3797, 0.8193, 0.4296);

float sm(float x) {
    float c = clamp(x, 0.0, 1.0);
    return c * c * (3.0 - 2.0 * c);
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float vnoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(hash13(i), hash13(i + vec3(1.0, 0.0, 0.0)), f.x),
                  mix(hash13(i + vec3(0.0, 1.0, 0.0)), hash13(i + vec3(1.0, 1.0, 0.0)), f.x), f.y);
    float b = mix(mix(hash13(i + vec3(0.0, 0.0, 1.0)), hash13(i + vec3(1.0, 0.0, 1.0)), f.x),
                  mix(hash13(i + vec3(0.0, 1.0, 1.0)), hash13(i + vec3(1.0, 1.0, 1.0)), f.x), f.y);
    return mix(a, b, f.z);
}

float fbm(vec3 p) {
    float a = 0.5;
    float s = 0.0;
    for (int i = 0; i < 4; i++) {
        s += a * vnoise(p);
        p = p * 2.03 + vec3(17.1, 3.7, 9.2);
        a *= 0.5;
    }
    return s;
}

vec2 rot(vec2 v, float a) {
    float c = cos(a);
    float s = sin(a);
    return vec2(c * v.x - s * v.y, s * v.x + c * v.y);
}

// ---------------------------------------------------------------------------------------------------------------
// Storm clouds. Returns (density 0..1, brightness 0..1). p is camera relative; Y is converted to absolute inside.
// ---------------------------------------------------------------------------------------------------------------

float vband(float y, float lo, float hi, float soft) {
    return sm((y - lo) / soft) * (1.0 - sm((y - hi) / soft));
}

vec2 stormField(vec3 p, int i) {
    vec4 a = SA[i];
    vec4 b = SB[i];
    float R = a.z;
    float dev = a.w;
    float I = b.x;
    vec2 q = p.xz - a.xy;
    float len = length(q);
    if (len > R * 2.3) {
        return vec2(0.0);
    }
    float y = p.y + CamPos.y;
    float alt = Params.x;
    float time = CamPos.w;

    float shape = 0.0;
    float bright = 0.55;
    float erosion = 0.5;
    vec2 core = a.xy;      // what the cloud features rotate about
    float rotation = 0.0;

    if (b.y < 0.5) {
        // ----- plain storm cell: a soft slab with a darker core -----
        float rr = len / R;
        float n0 = vnoise(vec3(q.x, y * 2.0, q.y) * 0.012);
        float radial = 1.0 - sm((rr - 0.5 + (n0 - 0.5) * 0.35) / 0.5);
        float v = (y - (alt - 8.0)) / 34.0;
        float vprof = sm(v / 0.18) * (1.0 - sm((v - 0.65) / 0.35));
        shape = radial * vprof * clamp(I * 1.4, 0.0, 1.0);
        bright = mix(0.62, 0.52 - 0.26 * I, radial);
        rotation = -time * 0.03;
    } else {
        // ----- supercell, built from the same parts as the particle version -----
        vec2 f = b.zw;
        vec2 s = vec2(-f.y, f.x);
        float tall = (20.0 + 130.0 * sm((dev - 0.03) / 0.75)) * clamp(R / 350.0, 1.0, 2.0);
        float anvilGrow = sm((dev - 0.28) / 0.5);
        float slotGrow = sm((dev - 0.5) / 0.4);
        vec2 wallPos = a.xy - f * R * 0.25;
        core = wallPos;
        float aa = dot(q, f);
        float bb = dot(q, s);

        float sumS = 0.0;
        float sumL = 0.0;

        // BASE: flat dark base, lowest and darkest in the forward flank, with a clear slot behind the wall cloud.
        {
            float rb = R * (0.22 + 0.78 * sm(dev / 0.55));
            float rr = len / rb;
            vec2 fq = q - f * R * 0.30;
            float ff = max(0.0, 1.0 - length(fq) / (R * 0.55));
            float bottom = alt - 4.0 - 5.0 * (1.0 - min(rr, 1.0)) - 9.0 * ff;
            float sv = sm((y - (bottom - 3.0)) / 8.0) * (1.0 - sm((y - (bottom + 34.0 - 12.0)) / 12.0));
            float nb = vnoise(vec3(q.x, y * 0.5, q.y) * 0.011);
            float radial = 1.0 - sm((rr - 0.62 + (nb - 0.5) * 0.55) / 0.3);
            vec2 slotC = wallPos - f * R * 0.10 + s * R * 0.20;
            float slotR = max(1.0, R * 0.11 * slotGrow);
            float slot = slotGrow > 0.02 ? 0.55 + 0.45 * sm((length(p.xz - slotC) + (nb - 0.5) * slotR * 0.5 - slotR * 0.35) / (slotR * 0.65)) : 1.0;
            float sh = radial * sv * slot * clamp(0.35 + I, 0.0, 1.0);
            sumS += sh;
            sumL += sh * (0.52 - 0.26 * I * (1.0 - 0.5 * min(rr, 1.0)) - 0.05 * ff * I);
        }
        // UPDRAFT TOWER: lumpy column leaning downwind with height, dark at the bottom, white at the top.
        {
            float h = clamp((y - (alt + 4.0)) / tall, 0.0, 1.0);
            float bulge = 0.12 + 0.22 * pow(sin(PI * min(1.0, h * 0.9 + 0.1)), 0.7);
            vec2 axis = a.xy + f * (R * 0.08 + h * R * 0.14 * dev);
            float rad = length(p.xz - axis) / (R * bulge);
            float inside = step(alt + 2.0, y) * (1.0 - sm((y - (alt + 4.0 + tall)) / 6.0));
            float sh = (1.0 - sm((rad - 0.55) / 0.45)) * inside * sm((dev - 0.02) / 0.1);
            sumS += sh;
            sumL += sh * (0.40 + 0.55 * h);
        }
        // OVERSHOOTING TOP
        {
            float over = 22.0 * sm((dev - 0.4) / 0.4) * clamp(R / 350.0, 1.0, 2.0);
            float topY = alt + 4.0 + tall;
            float lean = R * 0.14 * dev;
            vec2 axis = a.xy + f * (R * 0.08 + lean);
            float rad = length(p.xz - axis) / (R * 0.07);
            float sh = (1.0 - sm((rad - 0.4) / 0.6)) * sm((y - (topY - 4.0)) / 4.0) * (1.0 - sm((y - (topY + over)) / 5.0))
                       * step(0.02, over);
            sumS += sh;
            sumL += sh * 0.97;
        }
        // ANVIL and MAMMATUS
        if (anvilGrow > 0.01) {
            float g = R * anvilGrow;
            float t0 = (aa / g + 0.35) / 1.95;
            float half_ = g * (0.30 + 0.65 * pow(sin(PI * clamp(t0 * 1.1, 0.0, 1.0)), 0.8));
            float anvilY = alt + 4.0 + tall * 0.95;
            float across = abs(bb) / max(half_, 1.0);
            float planform = (1.0 - sm((across - 0.72) / 0.28)) * sm(t0 / 0.08) * (1.0 - sm((t0 - 0.9) / 0.1));
            float sh = planform * vband(y, anvilY - 4.0, anvilY + 12.0, 3.5) * 0.95;
            sumS += sh;
            sumL += sh * 0.90;
            if (dev > 0.55) {
                float outer = sm((t0 - 0.35) / 0.25);
                float lobe = vnoise(vec3(p.x, 0.0, p.z) * 0.04 + vec3(7.0, 0.0, 3.0));
                float hang = lobe * 11.0;
                float mam = (1.0 - sm((length(p) - 400.0) / 900.0)) * planform * outer * step(y, anvilY - 3.0 + 1.0) * sm((y - (anvilY - 4.0 - hang)) / 2.5) * smoothstep(0.35, 0.6, lobe);
                sumS += mam * 0.85;
                sumL += mam * 0.85 * 0.66;
            }
        }
        // SHELF (gust front): a low, dark, banded arc along the leading edge.
        if (dev > 0.45) {
            float ang = atan(bb, aa);
            float rr = len / R;
            float arc = (1.0 - sm((abs(rr - 0.86) - 0.07) / 0.14)) * (1.0 - sm((abs(ang) - 1.0) / 0.22));
            float sh = arc * vband(y, alt - 16.0, alt + 8.0, 6.0) * sm((dev - 0.45) / 0.3);
            sumS += sh;
            sumL += sh * (0.46 - 0.12 * I);
        }
        // WALL CLOUD (a lowering of the base, lowest in the middle) and TAIL CLOUD
        if (SC[i].z > 0.5) {
            vec4 w = SC[i];
            float Rw = w.z;
            vec2 wq = p.xz - w.xy;
            float wn = vnoise(vec3(wq.x, y * 0.6, wq.y) * 0.03);
            float fr = length(wq) / Rw + (wn - 0.5) * 0.35;
            float ceilY = alt - 6.0;
            float lip = 16.0 * (1.0 - sm((fr - 0.05) / 0.3));          // lower collar where the funnel attaches
            float bowl = w.w + (ceilY - w.w) * pow(clamp(fr, 0.0, 1.1), 1.3) - lip;
            float ring = 1.0 - sm((fr - 0.8) / 0.3);
            float sh = ring * sm((y - bowl) / 5.0) * (1.0 - sm((y - (ceilY + 8.0)) / 6.0));
            float wang = atan(wq.y, wq.x);
            float stri = 0.5 + 0.5 * sin(wang * 5.0 + fr * 9.0 - time * 0.5);
            sumS += sh * 1.5;
            sumL += sh * 1.5 * (0.20 + 0.30 * fr + 0.10 * stri);

            // tail: curves round from the wall cloud toward the rain area, rising as it leaves
            float fwd = atan(f.y, f.x);
            float phi = atan(wq.y, wq.x);
            float rho = length(wq) / Rw;
            float u = (rho - 0.9) / 1.4;
            float dAng = phi - (fwd - 0.9 * u);
            dAng = atan(sin(dAng), cos(dAng));
            float lateral = abs(rho * sin(dAng));
            float ty = w.w + (ceilY - w.w) * 0.55 * u;
            float tail = (1.0 - sm((lateral - 0.03) / 0.07)) * sm(u / 0.1) * (1.0 - sm((u - 0.9) / 0.1))
                         * vband(y, ty - 4.0, ty + 5.0, 2.0) * 0.8;
            sumS += tail;
            sumL += tail * 0.5;
        }
        shape = min(sumS, 1.0);
        bright = sumL / max(sumS, 0.0001);
        erosion = 0.5;
        rotation = -time * 0.14;
    }

    if (shape < 0.01) {
        return vec2(0.0);
    }
    // Slow anticlockwise rotation about the wall cloud, stronger near it.
    vec2 rq = p.xz - core;
    float rn = length(rq) / max(R * 0.22, 1.0);
    float swirl = rotation / (1.0 + rn * rn);
    rq = rot(rq, swirl);
    vec3 np = vec3(rq.x, y, rq.y);
    float n = fbm(np * 0.028);
    float fine = vnoise(np * 0.12);
    float dens = clamp((shape - (1.0 - n) * erosion - (1.0 - fine) * 0.12) * 2.3, 0.0, 1.0);
    return vec2(dens, clamp(bright + (n - 0.5) * 0.12, 0.0, 1.0));
}

vec2 allStorms(vec3 p) {
    float d = 0.0;
    float bl = 0.0;
    int n = int(Params.y);
    for (int i = 0; i < MAX_STORMS; i++) {
        if (i >= n) {
            break;
        }
        vec2 f = stormField(p, i);
        d += f.x;
        bl += f.x * f.y;
    }
    return vec2(min(d, 1.0), d > 0.0001 ? bl / d : 0.5);
}

// ---------------------------------------------------------------------------------------------------------------
// Tornado funnel and dust skirt.
// ---------------------------------------------------------------------------------------------------------------

float funnelProfile(float size, float wind, float h) {
    if (size >= 60.0) {
        return 0.50 + 0.18 * h + 0.9 * h * h * h * h;
    }
    if (wind >= 166.0 && size >= 8.0) {
        return 0.30 + 0.08 * h + 1.0 * pow(h, 6.0);
    }
    return 0.16 + 0.22 * h + 1.25 * h * h * h * h * h;
}

// Returns density; colour is written to col (rgb, before lighting).
float funnelField(vec3 p, int k, out vec3 col) {
    vec4 a = TA[k];
    vec4 b = TB[k];
    vec2 dir = TC[k].xy;
    float size = a.z;
    float baseY = a.w;
    float topY = b.x;
    float H = max(topY - baseY, 1.0);
    float I = b.y;
    float wind = b.z;
    float time = CamPos.w;
    float y = p.y + CamPos.y;
    float h = (y - baseY) / H;
    col = vec3(0.6);

    // axis: leans back along the direction of travel, with a slow sway that is stronger while weak
    vec2 lean = -dir * H * 0.10 * h * h;
    vec2 sway = vec2(sin(h * 4.0 + time * 0.6 + float(k)), cos(h * 3.1 + time * 0.5)) * H * 0.02 * (1.2 - min(1.0, I / 0.6));
    vec2 q = p.xz - (a.xy + lean + sway);
    float r = length(q);

    float dens = 0.0;
    if (h > -0.02 && h < 1.02) {
        float hc = clamp(h, 0.0, 1.0);
        float reach = clamp(I / 0.6, 0.0, 1.0);
        float lowest = 1.0 - reach;
        float taper = reach >= 1.0 ? 1.0 : 0.15 + 0.85 * sm((hc - lowest) / 0.14);
        float rad = max(size * funnelProfile(size, wind, hc) * taper, 0.6);
        float rho = r / rad;
        float core = 1.0 - sm((rho - 0.55) / 0.45);
        float spin = 3.0 / (0.3 + hc);
        float tw = atan(q.y, q.x) * 2.0 + hc * 6.0 - time * spin * 0.35;
        vec3 c = vec3(cos(tw), sin(tw), y * 0.03) * (1.0 + rho);
        float n = fbm(c * 1.6);
        float body = clamp((core - (1.0 - n) * 0.45) * 2.2, 0.0, 1.0);
        float vt = sm((h + 0.02) / 0.05) * (1.0 - sm((h - 0.97) / 0.05));
        dens = body * vt;
        // brown at the ground, grey in the middle, dark slate at the cloud; darker toward the core
        vec3 brown = vec3(0.50, 0.40, 0.30);
        vec3 grey = vec3(0.66, 0.68, 0.72);
        vec3 slate = vec3(0.34, 0.37, 0.43);
        col = mix(mix(brown, grey, sm(hc / 0.35)), slate, sm((hc - 0.5) / 0.5));
        col *= 0.55 + 0.45 * min(1.0, 1.0 - core * 0.5 + n * 0.4);
    }
    // dust skirt around the base
    float dr = clamp(size * 2.4, 6.0, 20000.0);
    float dh = clamp(size * 1.1, 5.0, 220.0);
    float yy = y - baseY;
    if (yy > -2.0 && yy < dh * 1.5) {
        float dn = fbm(vec3(p.x, y * 1.5, p.z) * (0.4 / max(1.0, size * 0.04)) + vec3(0.0, time * 0.4, 0.0));
        float dust = (1.0 - sm((r / dr - 0.3) / 0.7)) * (1.0 - sm(yy / dh)) * clamp((dn - 0.25) * 2.0, 0.0, 1.0);
        dust *= sm((yy + 2.0) / 3.0) * clamp(I * 2.0, 0.0, 1.0);
        if (dust > dens) {
            col = mix(col, vec3(0.46, 0.37, 0.28), clamp(dust / max(dust + dens, 0.001), 0.0, 1.0));
            dens = max(dens, dust * 0.85);
        }
    }
    return dens;
}

// ---------------------------------------------------------------------------------------------------------------
// Ray helpers
// ---------------------------------------------------------------------------------------------------------------

// Intersect the ray (origin = camera, so relative origin is 0) with a vertical cylinder and a Y slab.
// Returns (tNear, tFar); tFar < tNear when missed.
vec2 cylinderSlab(vec3 rd, vec2 c, float rad, float y0, float y1) {
    float camY = CamPos.y;
    vec2 o = -c;                                 // ray origin relative to the cylinder axis
    vec2 d = rd.xz;
    float A = dot(d, d);
    float B = dot(o, d);
    float C = dot(o, o) - rad * rad;
    float tN = 0.0;
    float tF = 1.0e9;
    if (A > 1.0e-8) {
        float disc = B * B - A * C;
        if (disc < 0.0) {
            return vec2(1.0, 0.0);
        }
        float sq = sqrt(disc);
        tN = (-B - sq) / A;
        tF = (-B + sq) / A;
    } else if (C > 0.0) {
        return vec2(1.0, 0.0);
    }
    if (abs(rd.y) > 1.0e-6) {
        float ta = (y0 - camY) / rd.y;
        float tb = (y1 - camY) / rd.y;
        tN = max(tN, min(ta, tb));
        tF = min(tF, max(ta, tb));
    } else if (camY < y0 || camY > y1) {
        return vec2(1.0, 0.0);
    }
    return vec2(max(tN, 0.0), tF);
}

float interleaved(vec2 fc) {
    return fract(52.9829189 * fract(dot(fc, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec3 vdir = normalize(viewDir);
    vec3 rd = normalize(mat3(InvView) * viewDir);
    float maxDist = Cfg.y;

    // How far away is the terrain along this ray? (Only when the depth of the main target is available.)
    float tTerrain = 1.0e9;
#ifdef HAS_DEPTH
    float depth = texture(MainDepthSampler, texCoord).r;
    if (depth < 1.0) {
        vec2 ndc = texCoord * 2.0 - 1.0;
        float nz = Cfg.z > 0.5 ? depth : depth * 2.0 - 1.0;
        vec4 pv = invProj * vec4(ndc, nz, 1.0);
        tTerrain = length(pv.xyz / pv.w);
    }
#endif
    float tLimit = min(maxDist, tTerrain);

    float jitter = interleaved(gl_FragCoord.xy + vec2(fract(CamPos.w * 7.31) * 64.0));
    float alt = Params.x;

    // ---- storm layer: union of every storm's bounding interval ----
    float sStart = 1.0e9;
    float sEnd = -1.0;
    int ns = int(Params.y);
    for (int i = 0; i < MAX_STORMS; i++) {
        if (i >= ns) {
            break;
        }
        float R = SA[i].z;
        bool sc = SB[i].y > 0.5;
        float y0 = alt - 14.0;
        float y1 = sc ? alt + 4.0 + 150.0 * clamp(R / 350.0, 1.0, 2.0) + 40.0 * clamp(R / 350.0, 1.0, 2.0) : alt + 30.0;
        vec2 iv = cylinderSlab(rd, SA[i].xy, R * (sc ? 2.1 : 1.05), y0, y1);
        if (iv.y > iv.x) {
            sStart = min(sStart, iv.x);
            sEnd = max(sEnd, iv.y);
        }
    }
    vec4 storm = vec4(0.0);
    float tStorm = 1.0e9;
    if (sEnd > sStart && sStart < tLimit) {
        float t0 = sStart;
        float t1 = min(sEnd, tLimit);
        int maxSteps = int(Cfg.x);
        float T = 1.0;
        vec3 acc = vec3(0.0);
        float t = t0;
        float sigma = 0.085 * Cfg.w;
        float span = max(t1 - t0, 1.0);
        for (int k = 0; k < 160; k++) {
            if (k >= maxSteps || t > t1 || T < 0.02) {
                break;
            }
            float dt = max(clamp(t * 0.03, 3.0, 90.0), span / float(maxSteps));
            float ts = t + dt * jitter;
            vec3 p = rd * ts;
            vec2 f = allStorms(p);
            if (f.x > 0.004) {
                float a = 1.0 - exp(-f.x * sigma * dt);
                // two taps toward the sun give self-shadowing
                float tau = 0.0;
                if (f.x > 0.02) {
                    float j = 0.75 + 0.5 * jitter;
                    tau = allStorms(p + SUN * (18.0 * j)).x * 18.0
                        + allStorms(p + SUN * (55.0 * j)).x * 37.0
                        + allStorms(p + SUN * (130.0 * j)).x * 75.0;
                }
                float lit = exp(-tau * sigma * 0.55);
                float shade = f.y * (0.50 + 0.50 * lit);
                vec3 tint = mix(vec3(0.58, 0.63, 0.74), vec3(1.0), clamp(f.y * 1.2, 0.0, 1.0));
                vec3 col = LightCol.rgb * tint * shade;
                acc += T * a * col;
                if (tStorm > 1.0e8 && T < 0.7) {
                    tStorm = ts;
                }
                T *= 1.0 - a;
            }
            t += dt;
        }
        storm = vec4(acc, 1.0 - T);
    }

    // ---- tornado funnels ----
    vec4 funnel = vec4(0.0);
    float tFunnel = 1.0e9;
    int nt = int(Params.z);
    for (int k = 0; k < MAX_TORN; k++) {
        if (k >= nt) {
            break;
        }
        float size = TA[k].z;
        float baseY = TA[k].w;
        float topY = TB[k].x;
        float H = max(topY - baseY, 1.0);
        float rmax = size * 2.7 + H * 0.15 + 20.0;
        vec2 iv = cylinderSlab(rd, TA[k].xy, rmax, baseY - 2.0, topY + 4.0);
        if (!(iv.y > iv.x) || iv.x > tLimit) {
            continue;
        }
        float t0 = iv.x;
        float t1 = min(iv.y, tLimit);
        float T = 1.0 - funnel.a;
        vec3 acc = funnel.rgb;
        float dt = max((t1 - t0) / 48.0, 0.5);
        float t = t0 + dt * jitter;
        for (int s = 0; s < 64; s++) {
            if (t > t1 || T < 0.02) {
                break;
            }
            vec3 p = rd * t;
            vec3 c;
            float d = funnelField(p, k, c);
            if (d > 0.004) {
                float a = 1.0 - exp(-d * 0.16 * Cfg.w * dt);
                float shadow = 0.62 + 0.38 * clamp(p.y * 0.0 + (1.0 - d * 0.7), 0.0, 1.0);
                acc += T * a * c * shadow * LightCol.rgb;
                if (tFunnel > 1.0e8) {
                    tFunnel = t;
                }
                T *= 1.0 - a;
            }
            t += dt;
        }
        funnel = vec4(acc, 1.0 - T);
    }

    // ---- composite, nearer thing first ----
    vec4 outc;
    float tHit;
    if (tFunnel < tStorm) {
        outc = vec4(funnel.rgb + (1.0 - funnel.a) * storm.rgb, funnel.a + (1.0 - funnel.a) * storm.a);
        tHit = tFunnel;
    } else {
        outc = vec4(storm.rgb + (1.0 - storm.a) * funnel.rgb, storm.a + (1.0 - storm.a) * funnel.a);
        tHit = min(tStorm, tFunnel);
    }
    if (outc.a < 0.004 || tHit > 1.0e8) {
        discard;
    }

    // fade out toward the maximum distance so the far edge does not pop
    float fade = 1.0 - sm((tHit / maxDist - 0.6) / 0.4);
    outc *= fade;

    // depth of the first solid-ish hit, so vanilla's transparency pass sorts this against particles and terrain
    vec4 clip = ProjMat * vec4(vdir * tHit, 1.0);
    float ndcZ = clip.z / clip.w;
    gl_FragDepth = clamp(Cfg.z > 0.5 ? ndcZ : ndcZ * 0.5 + 0.5, 0.0, 1.0);
    fragColor = outc;
}
