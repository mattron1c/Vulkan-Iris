#version 330 compatibility
const int shadowMapResolution = 256;
const bool shadowHardwareFiltering = true;
const bool shadowcolor0Clear = false;
/*
const int shadowcolor0Format = RGBA8;
const int shadowcolor1Format = RGBA8;
*/
/* DRAWBUFFERS:0 */
void main() {
	gl_FragData[0] = vec4(0.1, 0.2, 0.3, 1.0);
}
