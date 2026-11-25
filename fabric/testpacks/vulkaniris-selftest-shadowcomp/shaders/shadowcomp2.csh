#version 430 compatibility
// Index 2, compute only: shadowcolor0 and 1 are both flipped, so shadowcolorimg0 is shadowcolor0's alt.
layout(local_size_x = 1, local_size_y = 1) in;
const ivec3 workGroups = ivec3(1, 1, 1);
layout(rgba8) uniform image2D shadowcolorimg0;
void main() {
	imageStore(shadowcolorimg0, ivec2(0, 0), vec4(0.2, 0.7, 0.0, 1.0));
}
