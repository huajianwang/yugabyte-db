import{_ as i}from"./extends-98964cd2.js";import{_ as G,m as O,h as I,c as K,d as H}from"./capitalize-6c71ac81.js";import{m as k,d as E,o as C,_ as R,s as _}from"./useTheme-ed20be1d.js";import{R as S}from"./index-f2bd0723.js";import"./index-e297e3bd.js";function A(o,t){return!t||typeof t!="string"?null:t.split(".").reduce(function(a,e){return a&&a[e]?a[e]:null},o)}function r(o){var t=o.prop,a=o.cssProperty,e=a===void 0?o.prop:a,n=o.themeKey,s=o.transform,d=function(f){if(f[t]==null)return null;var w=f[t],h=f.theme,u=A(h,n)||{},b=function(m){var p;return typeof u=="function"?p=u(m):Array.isArray(u)?p=u[m]||m:(p=A(u,m)||m,s&&(p=s(p))),e===!1?p:E({},e,p)};return k(f,w,b)};return d.propTypes={},d.filterProps=[t],d}function l(){for(var o=arguments.length,t=new Array(o),a=0;a<o;a++)t[a]=arguments[a];var e=function(s){return t.reduce(function(d,x){var f=x(s);return f?C(d,f):d},{})};return e.propTypes={},e.filterProps=t.reduce(function(n,s){return n.concat(s.filterProps)},[]),e}function y(o){return typeof o!="number"?o:"".concat(o,"px solid")}var D=r({prop:"border",themeKey:"borders",transform:y}),F=r({prop:"borderTop",themeKey:"borders",transform:y}),L=r({prop:"borderRight",themeKey:"borders",transform:y}),M=r({prop:"borderBottom",themeKey:"borders",transform:y}),q=r({prop:"borderLeft",themeKey:"borders",transform:y}),J=r({prop:"borderColor",themeKey:"palette"}),Q=r({prop:"borderRadius",themeKey:"shape"}),U=l(D,F,L,M,q,J,Q);const X=U;function T(o,t){var a={};return Object.keys(o).forEach(function(e){t.indexOf(e)===-1&&(a[e]=o[e])}),a}function Y(o){var t=function(e){var n=o(e);return e.css?i({},C(n,o(i({theme:e.theme},e.css))),T(e.css,[o.filterProps])):e.sx?i({},C(n,o(i({theme:e.theme},e.sx))),T(e.sx,[o.filterProps])):n};return t.propTypes={},t.filterProps=["css","sx"].concat(G(o.filterProps)),t}var Z=r({prop:"displayPrint",cssProperty:!1,transform:function(t){return{"@media print":{display:t}}}}),V=r({prop:"display"}),rr=r({prop:"overflow"}),er=r({prop:"textOverflow"}),or=r({prop:"visibility"}),tr=r({prop:"whiteSpace"});const ar=l(Z,V,rr,er,or,tr);var nr=r({prop:"flexBasis"}),pr=r({prop:"flexDirection"}),ir=r({prop:"flexWrap"}),sr=r({prop:"justifyContent"}),fr=r({prop:"alignItems"}),mr=r({prop:"alignContent"}),lr=r({prop:"order"}),dr=r({prop:"flex"}),ur=r({prop:"flexGrow"}),vr=r({prop:"flexShrink"}),cr=r({prop:"alignSelf"}),hr=r({prop:"justifyItems"}),gr=r({prop:"justifySelf"}),yr=l(nr,pr,ir,sr,fr,mr,lr,dr,ur,vr,cr,hr,gr);const xr=yr;var br=r({prop:"gridGap"}),wr=r({prop:"gridColumnGap"}),Pr=r({prop:"gridRowGap"}),Sr=r({prop:"gridColumn"}),Cr=r({prop:"gridRow"}),Kr=r({prop:"gridAutoFlow"}),Rr=r({prop:"gridAutoColumns"}),Ar=r({prop:"gridAutoRows"}),Tr=r({prop:"gridTemplateColumns"}),Nr=r({prop:"gridTemplateRows"}),zr=r({prop:"gridTemplateAreas"}),$r=r({prop:"gridArea"}),Br=l(br,wr,Pr,Sr,Cr,Kr,Rr,Ar,Tr,Nr,zr,$r);const jr=Br;var Wr=r({prop:"color",themeKey:"palette"}),Gr=r({prop:"bgcolor",cssProperty:"backgroundColor",themeKey:"palette"}),Or=l(Wr,Gr);const Ir=Or;var Hr=r({prop:"position"}),kr=r({prop:"zIndex",themeKey:"zIndex"}),Er=r({prop:"top"}),_r=r({prop:"right"}),Dr=r({prop:"bottom"}),Fr=r({prop:"left"});const Lr=l(Hr,kr,Er,_r,Dr,Fr);var Mr=r({prop:"boxShadow",themeKey:"shadows"});const qr=Mr;function c(o){return o<=1?"".concat(o*100,"%"):o}var Jr=r({prop:"width",transform:c}),Qr=r({prop:"maxWidth",transform:c}),Ur=r({prop:"minWidth",transform:c}),Xr=r({prop:"height",transform:c}),Yr=r({prop:"maxHeight",transform:c}),Zr=r({prop:"minHeight",transform:c});r({prop:"size",cssProperty:"width",transform:c});r({prop:"size",cssProperty:"height",transform:c});var Vr=r({prop:"boxSizing"}),re=l(Jr,Qr,Ur,Xr,Yr,Zr,Vr);const ee=re;var oe=r({prop:"fontFamily",themeKey:"typography"}),te=r({prop:"fontSize",themeKey:"typography"}),ae=r({prop:"fontStyle",themeKey:"typography"}),ne=r({prop:"fontWeight",themeKey:"typography"}),pe=r({prop:"letterSpacing"}),ie=r({prop:"lineHeight"}),se=r({prop:"textAlign"}),fe=l(oe,te,ae,ne,pe,ie,se);const me=fe;function le(o,t){var a={};return Object.keys(o).forEach(function(e){t.indexOf(e)===-1&&(a[e]=o[e])}),a}function de(o){var t=function(e){var n=arguments.length>1&&arguments[1]!==void 0?arguments[1]:{},s=n.name,d=R(n,["name"]),x=s,f=typeof e=="function"?function(b){return{root:function(m){return e(i({theme:b},m))}}}:{root:e},w=O(f,i({Component:o,name:s||o.displayName,classNamePrefix:x},d)),h;e.filterProps&&(h=e.filterProps,delete e.filterProps),e.propTypes&&(e.propTypes,delete e.propTypes);var u=S.forwardRef(function(v,m){var p=v.children,N=v.className,z=v.clone,$=v.component,B=R(v,["children","className","clone","component"]),j=w(v),P=K(j.root,N),g=B;if(h&&(g=le(g,h)),z)return S.cloneElement(p,i({className:K(p.props.className,P)},g));if(typeof p=="function")return p(i({className:P},g));var W=$||o;return S.createElement(W,i({ref:m,className:P},g),p)});return I(u,o),u};return t}var ue=function(t){var a=de(t);return function(e,n){return a(e,i({defaultTheme:H},n))}};const ve=ue;var ce=Y(l(X,ar,xr,jr,Lr,Ir,qr,ee,_,me)),he=ve("div")(ce,{name:"MuiBox"});const Pe=he;export{Pe as B};
//# sourceMappingURL=Box-7cca7bfc.js.map
