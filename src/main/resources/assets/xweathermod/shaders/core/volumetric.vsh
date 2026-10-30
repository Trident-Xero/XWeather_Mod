#version 330

// XWeather volumetric clouds: full-screen triangle (no vertex buffer), same idea as vanilla core/screenquad.
// Everything is declared in this file on purpose (no #moj_import), so it does not depend on vanilla include files.

layout(std140) uniform Projection {
    mat4 ProjMat;
};

out vec2 texCoord;
out vec3 viewDir;
flat out mat4 invProj;

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vec4 pos = vec4(uv * vec2(2.0, 2.0) + vec2(-1.0, -1.0), 0.0, 1.0);
    gl_Position = pos;
    texCoord = uv;

    // Direction of the view ray through this corner (view space). Linear across the triangle, so it
    // interpolates exactly to the per-pixel ray.
    mat4 ip = inverse(ProjMat);
    invProj = ip;
    vec4 p = ip * vec4(pos.xy, 0.0, 1.0);
    viewDir = p.xyz / p.w;
}
