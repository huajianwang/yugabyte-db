import{o as T}from"./ownerDocument-613eb639.js";var t={};/** @license React v17.0.2
 * react-is.production.min.js
 *
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */var y=60103,$=60106,c=60107,u=60108,i=60114,f=60109,s=60110,a=60112,l=60113,w=60120,m=60115,p=60116,C=60121,S=60122,M=60117,P=60129,F=60131;if(typeof Symbol=="function"&&Symbol.for){var r=Symbol.for;y=r("react.element"),$=r("react.portal"),c=r("react.fragment"),u=r("react.strict_mode"),i=r("react.profiler"),f=r("react.provider"),s=r("react.context"),a=r("react.forward_ref"),l=r("react.suspense"),w=r("react.suspense_list"),m=r("react.memo"),p=r("react.lazy"),C=r("react.block"),S=r("react.server.block"),M=r("react.fundamental"),P=r("react.debug_trace_mode"),F=r("react.legacy_hidden")}function n(e){if(typeof e=="object"&&e!==null){var o=e.$$typeof;switch(o){case y:switch(e=e.type,e){case c:case i:case u:case l:case w:return e;default:switch(e=e&&e.$$typeof,e){case s:case a:case p:case m:case f:return e;default:return o}}case $:return o}}}var _=f,j=y,A=a,D=c,I=p,L=m,R=$,V=i,q=u,B=l;t.ContextConsumer=s;t.ContextProvider=_;t.Element=j;t.ForwardRef=A;t.Fragment=D;t.Lazy=I;t.Memo=L;t.Portal=R;t.Profiler=V;t.StrictMode=q;t.Suspense=B;t.isAsyncMode=function(){return!1};t.isConcurrentMode=function(){return!1};t.isContextConsumer=function(e){return n(e)===s};t.isContextProvider=function(e){return n(e)===f};t.isElement=function(e){return typeof e=="object"&&e!==null&&e.$$typeof===y};t.isForwardRef=function(e){return n(e)===a};t.isFragment=function(e){return n(e)===c};t.isLazy=function(e){return n(e)===p};t.isMemo=function(e){return n(e)===m};t.isPortal=function(e){return n(e)===$};t.isProfiler=function(e){return n(e)===i};t.isStrictMode=function(e){return n(e)===u};t.isSuspense=function(e){return n(e)===l};t.isValidElementType=function(e){return typeof e=="string"||typeof e=="function"||e===c||e===i||e===P||e===u||e===l||e===w||e===F||typeof e=="object"&&e!==null&&(e.$$typeof===p||e.$$typeof===m||e.$$typeof===f||e.$$typeof===s||e.$$typeof===a||e.$$typeof===M||e.$$typeof===C||e[0]===S)};t.typeOf=n;function O(e){var o=arguments.length>1&&arguments[1]!==void 0?arguments[1]:166,v;function g(){for(var b=arguments.length,h=new Array(b),d=0;d<b;d++)h[d]=arguments[d];var z=this,E=function(){e.apply(z,h)};clearTimeout(v),v=setTimeout(E,o)}return g.clear=function(){clearTimeout(v)},g}function W(e){var o=T(e);return o.defaultView||window}export{O as d,W as o};
//# sourceMappingURL=ownerWindow-824ea88f.js.map
