in vec3 n;
in vec2 tex;
in vec3 sourcePosition;
in vec3 fragment3DPos;
in vec3 shadowPos;
in vec4 lightmapColorAndSkylight;
uniform int glassCount;
uniform vec3 glassMin[16],glassMax[16];
uniform sampler2D diffuse;
uniform sampler2DShadow shadowMap;
uniform bool glass,paintMask,transparentPass;
uniform float diffuseTint;
layout(location=0) out vec4 outColor;
void main(){
    vec4 c=texture(diffuse,tex);c.rgb*=diffuseTint;
    bool pane=glass;vec3 at=vec3(abs(sourcePosition.x),sourcePosition.yz);
    for(int i=0;i<glassCount;i++)pane=pane||(all(greaterThanEqual(at,glassMin[i]))&&all(lessThanEqual(at,glassMax[i])));
    float a=pane?0.32*max(0.65,c.a):(paintMask?1.0:c.a);
    if(transparentPass){if(a>=0.98||a<0.02)discard;}else{if(pane||a<0.98)discard;}
    vec3 normal=normalize(n),direction=normalize(fragment3DPos);float distanceToFragment=length(fragment3DPos);
    float shadow=ShadowCalculation(shadowPos,normal,distanceToFragment,shadowMap,NORMAL_SHADOW_SAMPLES);
    vec3 irradiance=lightmapColorAndSkylight.rgb*EnvDiffuseColor(normal,fragment3DPos)+SunDiffuseColor(normal,shadow);
    #if POINT_LIGHTS
        vec3 pointDiffuse;LightDiffuseColor(normal,fragment3DPos,pointDiffuse);irradiance+=pointDiffuse;
    #endif
    vec3 color=pow(max(c.rgb,vec3(0)),vec3(2.2))*max(irradiance,vec3(0));
    if(pane){vec3 reflected=reflect(direction,normal);color+=pow(max(textureLod(g_LightEnvMapSpecular,reflected,5.0).rgb,vec3(0)),vec3(2.2))*.1*lightmapColorAndSkylight.rgb;}
    color*=g_LightExposure;
    color=ComputeVolumetricFog(color,direction,distanceToFragment,fragment3DPos.y,lightmapColorAndSkylight.a);
    color=pow(max(toneMap(color),vec3(0)),vec3(0.454545));
    outColor=vec4(color,a);
}
