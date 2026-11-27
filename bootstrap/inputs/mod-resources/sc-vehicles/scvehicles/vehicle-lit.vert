layout(location=0) in vec3 position;layout(location=1) in vec3 normal;layout(location=2) in vec2 uv;layout(location=3) in vec4 trackFrame;
uniform mat4 view;uniform mat4 projection;uniform vec3 offset;uniform vec4 rotation;uniform vec4 partRotation;uniform vec3 pivot;
uniform bool movingTrack;uniform sampler2D trackPath;uniform float trackTravel;uniform float trackLength;
out vec3 n;out vec2 tex;out vec3 sourcePosition;out vec3 fragment3DPos;out vec3 shadowPos;out vec4 lightmapColorAndSkylight;
uniform vec3 cameraOrigin,lightBoxMin,lightBoxMax;uniform vec4 lightSamples[8];
vec3 rotate(vec4 q,vec3 p){return p+2.0*cross(q.xyz,cross(q.xyz,p)+q.w*p);}
void main(){
    sourcePosition=position;vec3 local=position,localNormal=normal;
    if(movingTrack){
        vec4 path=texture(trackPath,vec2(fract((trackFrame.x+trackTravel)/trackLength)+0.5/256.0,0.5));
        float angle=atan(path.w,path.z)-trackFrame.w;float c=cos(angle),s=sin(angle);mat2 turn=mat2(c,s,-s,c);
        local.yz=path.xy+turn*(position.yz-trackFrame.yz);localNormal.yz=turn*normal.yz;
    }
    vec3 p=rotate(partRotation,local-pivot)+pivot;gl_Position=projection*view*vec4(rotate(rotation,p)+offset,1);
    n=rotate(rotation,rotate(partRotation,localNormal));tex=uv;
    fragment3DPos=rotate(rotation,p)+offset-cameraOrigin;
    shadowPos=calcShadowPos(fragment3DPos,normalize(n));
    vec3 t=clamp((p-lightBoxMin)/(lightBoxMax-lightBoxMin),0.0,1.0);
    lightmapColorAndSkylight=mix(mix(mix(lightSamples[0],lightSamples[1],t.x),mix(lightSamples[2],lightSamples[3],t.x),t.y),mix(mix(lightSamples[4],lightSamples[5],t.x),mix(lightSamples[6],lightSamples[7],t.x),t.y),t.z);
}
