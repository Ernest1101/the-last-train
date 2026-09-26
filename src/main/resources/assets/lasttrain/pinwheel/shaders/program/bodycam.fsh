// Camera look ported from "SP-Backrooms Revamped" by SpacePotato (spb-revamped:vhs/vhs_post),
// licensed under LGPL-3.0-only - see LICENSE_spb-revamped next to the mod's jar sources.
// Barrel distortion parts originally from https://agatedragon.blog/2023/12/24/barrel-distortion-shader/
// and https://www.shadertoy.com/view/XtlSD7.
// The Last Train additions at the bottom: tearing near the monster (Fear), red flash (Scare),
// flashlight cone and the tunnel vision while holding the breath.

#include veil:deferred_utils

uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;
uniform sampler2D VhsNoise;

uniform float GameTime;
uniform mat4 prevViewMat;
uniform mat4 prevProjMat;
uniform vec3 prevCameraPos;
uniform float MotionBlurStrength;
uniform float DistortionStrength;

uniform float Time;
uniform float Fear;
uniform float Breath;
uniform float Scare;
uniform float Flashlight;
uniform float Rewind;

in vec2 texCoord;
out vec4 fragColor;

// ---- from spb-revamped:common
float hash12(vec2 p){
    vec3 p3  = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 rgb2yuv(vec3 rgb){
    float y = 0.299*rgb.r + 0.587*rgb.g + 0.114*rgb.b;
    return vec3(y, 0.493*(rgb.b-y), 0.877*(rgb.r-y));
}

vec3 yuv2rgb(vec3 yuv){
    float y = yuv.x;
    float u = yuv.y;
    float v = yuv.z;

    return vec3(
    y + 1.0/0.877*v,
    y - 0.39393*u - 0.58081*v,
    y + 1.0/0.493*u
    );
}

vec3 projectAndDivide(mat4 projMat, vec3 position){
    vec4 homogeneousPos = projMat * vec4(position, 1.0);
    return homogeneousPos.xyz / homogeneousPos.w;
}

// ---- from spb-revamped:vhs/vhs_post
vec2 BarrelDistortionCoordinates(vec2 uv) {
    vec2 pos = 2.0f * uv - 1.0f;

    float len = distance(pos, vec2(0.0f));
    len = pow(len/1.5f, 1.0f) * DistortionStrength;

    pos = pos + pos * len * len;

    pos = 0.5f * (pos + 1.0f);

    return pos;
}

void main() {
    // spb's vertex shader draws the screen quad with w = mix(1.0, 0.70, DistortionStrength), which zooms
    // into the middle of the distorted picture so the stretched edges stay off screen. Same thing here.
    vec2 tc = (texCoord - 0.5) * mix(1.0, 0.70, DistortionStrength) + 0.5;

    // tearing near the monster (The Last Train)
    float glitch = Fear * 0.5 + Scare;
    float line = floor(texCoord.y * 140.0);
    float tear = step(1.0 - 0.05 * glitch, hash12(vec2(line, floor(Time * 24.0))));
    tc.x += tear * (hash12(vec2(line, Time)) - 0.5) * 0.06 * glitch;

    // tape rewind after a death (The Last Train): every line wobbles sideways, tracking bands roll up the screen
    float rewindBand = 0.0;
    if (Rewind > 0.0) {
        float row = floor(texCoord.y * 220.0);
        tc.x += (hash12(vec2(row, floor(Time * 60.0))) - 0.5) * 0.012 * Rewind;
        rewindBand = smoothstep(0.90, 1.0, fract(texCoord.y * 2.3 - Time * 3.1));
        tc.x += rewindBand * 0.045 * Rewind * sin(texCoord.y * 90.0 + Time * 40.0);
        tc.y += (hash12(vec2(floor(Time * 24.0), 7.0)) - 0.5) * 0.02 * Rewind;
    }

    vec2 uv = BarrelDistortionCoordinates(tc);

    float depth = texture(DiffuseDepthSampler, uv).r;
    vec3 positionVS = viewPosFromDepthSample(depth, uv);
    vec3 NDCPos = projectAndDivide(VeilCamera.ProjMat, positionVS);

    vec3 cameraOffset = prevCameraPos - VeilCamera.CameraPosition;
    vec3 playerSpace = viewToPlayerSpace(positionVS);

    vec3 prevViewPos = (prevViewMat * vec4(playerSpace - cameraOffset, 1.0)).xyz;
    vec3 prevNDCPos = projectAndDivide(prevProjMat, prevViewPos);

    vec2 velocity = (NDCPos - prevNDCPos).xy;
    // our bodycam shake moves the camera a little every frame; only blur real, fast motion (The Last Train)
    float speed = length(velocity);
    velocity *= smoothstep(0.003, 0.012, speed);

    // Motion Blur
    vec4 blur3 = vec4(0.0);
    const float kernalSize3 = 5.0;
    const float coeff3 = 1.0 / (kernalSize3 * kernalSize3);
    for(float x = -1.0; x <= 1.0; x += coeff3){
        blur3 += coeff3 * texture(DiffuseSampler0, uv - vec2(velocity.x * x, velocity.y * x) * MotionBlurStrength * 0.25) * 0.5;
    }
    fragColor = blur3;

    // light sharpening against the softness of the 1.43x lens zoom (The Last Train)
    vec2 px = 1.0 / vec2(textureSize(DiffuseSampler0, 0));
    vec3 around = (texture(DiffuseSampler0, uv + vec2(px.x, 0.0)).rgb + texture(DiffuseSampler0, uv - vec2(px.x, 0.0)).rgb
            + texture(DiffuseSampler0, uv + vec2(0.0, px.y)).rgb + texture(DiffuseSampler0, uv - vec2(0.0, px.y)).rgb) * 0.25;
    fragColor.rgb += (fragColor.rgb - around) * 0.6;

    // chromatic aberration as in spb-revamped:vhs/bloom (horizontal, growing towards the sides) - made stronger
    float chromAbb = 0.006 * abs(tc.x - 0.5) + 0.004 * dot(tc - 0.5, tc - 0.5);
    fragColor.r = texture(DiffuseSampler0, uv + vec2(chromAbb, 0.0)).r * 0.5 + fragColor.r * 0.5;
    fragColor.g = texture(DiffuseSampler0, uv - vec2(chromAbb, 0.0)).g * 0.5 + fragColor.g * 0.5;

    // chromatic split while glitching / when struck (The Last Train)
    float ca = 0.004 * Fear + 0.012 * Scare;
    if (ca > 0.0) {
        fragColor.r = texture(DiffuseSampler0, uv + ca).r;
        fragColor.b = texture(DiffuseSampler0, uv - ca).b;
    }

    // flashlight cone (The Last Train)
    vec2 c = texCoord - 0.5;
    float cone = smoothstep(0.45, 0.05, length(c * vec2(1.25, 1.0)));
    fragColor.rgb = fragColor.rgb * (1.0 + Flashlight * cone * 2.6) + Flashlight * cone * vec3(0.07, 0.06, 0.04);

    //VHS POSST EFFECTS
    fragColor.rgb = rgb2yuv(fragColor.rgb);
    // (noise amounts raised from spb's 0.05 / 0.2 so it reads like their full pipeline)
    fragColor.rgb += (fragColor.rgb * vec3((hash12(uv * 260.23535 + GameTime * 70.0) + hash12(uv * 737.36346 + GameTime * 100.0)) - 1.0)) * 0.11;
    fragColor.r += step(0.99994, (hash12(uv * 260.23535 + GameTime * 70.0))) * 10.0;
    vec2 vhsNoise = texture(VhsNoise, vec2(uv.x - GameTime * 3000.0, uv.y + GameTime * 5000.0)).gb * 0.1;
    fragColor.gb += vec2(vhsNoise.x * 0.9, vhsNoise.y * 0.9) * 0.3;
    fragColor.gb += (vec2(hash12(uv * 911.3 + GameTime * 53.0), hash12(uv * 577.1 + GameTime * 91.0)) - 0.5) * 0.015;
    fragColor.rgb = yuv2rgb(fragColor.rgb);

    // a touch more saturation, faint cool cast, slightly darker corners (The Last Train)
    float l = dot(fragColor.rgb, vec3(0.299, 0.587, 0.114));
    fragColor.rgb = mix(vec3(l), fragColor.rgb, 1.05);
    fragColor.rgb *= vec3(0.99, 0.99, 1.02);
    fragColor.rgb *= mix(0.88, 1.0, smoothstep(0.8, 0.35, length((texCoord - 0.5) * vec2(1.2, 1.0))));

    // tunnel vision while holding the breath, red flash when struck (The Last Train)
    fragColor.rgb *= mix(1.0, smoothstep(0.7, 0.15, length(c)), Breath * 0.55);
    float luma = dot(fragColor.rgb, vec3(0.299, 0.587, 0.114));
    fragColor.rgb = mix(fragColor.rgb, vec3(luma * 1.4 + 0.2, 0.02, 0.02), Scare * 0.4);

    // rewind: washed out, with the white noise of the tracking bands on top
    float rl = dot(fragColor.rgb, vec3(0.299, 0.587, 0.114));
    fragColor.rgb = mix(fragColor.rgb, vec3(rl) * vec3(0.95, 1.0, 1.1), 0.6 * Rewind);
    fragColor.rgb += rewindBand * Rewind * (0.35 + 0.65 * hash12(uv * 640.0 + Time * 30.0));
    fragColor.a = 1.0;
}
