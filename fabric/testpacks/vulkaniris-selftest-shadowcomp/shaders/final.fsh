#version 330 compatibility
// Green when every check passes; otherwise red + blue = the failed checks' bit mask / 255.
uniform sampler2D shadowcolor0;
uniform sampler2D shadowcolor1;
uniform sampler2D shadowtex0;
uniform sampler2DShadow shadowtex0HW;
in vec2 texcoord;
bool near(vec3 a, vec3 b) {
	return all(lessThan(abs(a - b), vec3(0.02)));
}
void main() {
	int mask = 0;
	if (!near(texelFetch(shadowcolor0, ivec2(0, 0), 0).rgb, vec3(0.2, 0.7, 0.0))) mask |= 1;   // shadowcomp2.csh
	if (!near(texelFetch(shadowcolor0, ivec2(1, 0), 0).rgb, vec3(0.9, 0.1, 0.4))) mask |= 2;   // shadow.csh
	if (!near(texelFetch(shadowcolor0, ivec2(2, 0), 0).rgb, vec3(0.2, 0.0, 0.0))) mask |= 4;   // shadowcomp
	if (!near(texelFetch(shadowcolor1, ivec2(2, 0), 0).rgb, vec3(0.5, 1.0, 0.0))) mask |= 8;   // shadowcomp1
	if (texture(shadowtex0HW, vec3(0.5, 0.5, -1.0)) < 0.5) mask |= 16;                         // compare: ref below
	if (texture(shadowtex0HW, vec3(0.5, 0.5, 2.0)) > 0.5) mask |= 32;                          // compare: ref above
	if (textureSize(shadowtex0, 0).x != 256) mask |= 64;                                        // plain sampler
	gl_FragData[0] = mask == 0 ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(float(mask) / 255.0, 0.0, float(mask) / 255.0, 1.0);
}
