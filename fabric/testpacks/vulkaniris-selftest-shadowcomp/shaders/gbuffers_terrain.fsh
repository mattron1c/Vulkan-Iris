#version 330 compatibility
uniform sampler2D gtexture;
in vec2 uv;
in vec4 tint;
/* DRAWBUFFERS:0 */
void main() {
	gl_FragData[0] = texture(gtexture, uv) * tint;
}
