#version 330 compatibility
// Index 1: shadowcolor0 is flipped, so it reads "alt" (written by index 0 and by shadow.csh). Writes shadowcolor1's alt.
uniform sampler2D shadowcolor0;
/* DRAWBUFFERS:1 */
void main() {
	vec3 marker = texelFetch(shadowcolor0, ivec2(1, 0), 0).rgb;
	bool markerOk = all(lessThan(abs(marker - vec3(0.9, 0.1, 0.4)), vec3(0.02)));
	gl_FragData[0] = vec4(texelFetch(shadowcolor0, ivec2(2, 0), 0).r + 0.3, markerOk ? 1.0 : 0.0, 0.0, 1.0);
}
