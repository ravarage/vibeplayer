//!HOOK MAIN
//!BIND HOOKED
//!DESC Test Passthrough Shader for P8-01 Plumbing
vec4 hook() {
    return HOOKED_tex(HOOKED_pos);
}
