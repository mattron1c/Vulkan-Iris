#version 330 compatibility
// Index 0: reads nothing, writes shadowcolor0 (main is read, so "alt" is written), then shadowcolor0 flips.
/* DRAWBUFFERS:0 */
void main() {
	if (ivec2(gl_FragCoord.xy) == ivec2(1, 0)) discard;
	gl_FragData[0] = vec4(0.2, 0.0, 0.0, 1.0);
}
