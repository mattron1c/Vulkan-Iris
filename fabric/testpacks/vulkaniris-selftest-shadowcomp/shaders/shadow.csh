#version 430 compatibility
// Runs at frame start (Iris: after the shadow depth clear). shadowcolor0 ends up flipped, so this writes its "alt"
// texture, which no clear touches (shadowcolor0Clear = false) and shadowcomp keeps at texel (1, 0).
layout(local_size_x = 1, local_size_y = 1) in;
const ivec3 workGroups = ivec3(1, 1, 1);
layout(rgba8) uniform image2D shadowcolorimg0;
void main() {
	imageStore(shadowcolorimg0, ivec2(1, 0), vec4(0.9, 0.1, 0.4, 1.0));
}
