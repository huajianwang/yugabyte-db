import{_ as b}from"./useTheme-ed20be1d.js";import{_ as n}from"./extends-98964cd2.js";import{r as s}from"./index-f2bd0723.js";import"./index-e297e3bd.js";import{w as p,c,a as y}from"./capitalize-6c71ac81.js";import{a as F}from"./useFormControl-21824096.js";function v(l){var r=l.props,i=l.states,t=l.muiFormControl;return i.reduce(function(e,a){return e[a]=r[a],t&&typeof r[a]>"u"&&(e[a]=t[a]),e},{})}var q=function(r){return{root:n({color:r.palette.text.secondary},r.typography.body1,{lineHeight:1,padding:0,"&$focused":{color:r.palette.primary.main},"&$disabled":{color:r.palette.text.disabled},"&$error":{color:r.palette.error.main}}),colorSecondary:{"&$focused":{color:r.palette.secondary.main}},focused:{},disabled:{},error:{},filled:{},required:{},asterisk:{"&$error":{color:r.palette.error.main}}}},C=s.forwardRef(function(r,i){var t=r.children,e=r.classes,a=r.className;r.color;var d=r.component,m=d===void 0?"label":d;r.disabled,r.error,r.filled,r.focused,r.required;var u=b(r,["children","classes","className","color","component","disabled","error","filled","focused","required"]),f=F(),o=v({props:r,muiFormControl:f,states:["color","required","focused","disabled","error","filled"]});return s.createElement(m,n({className:c(e.root,e["color".concat(y(o.color||"primary"))],a,o.disabled&&e.disabled,o.error&&e.error,o.filled&&e.filled,o.focused&&e.focused,o.required&&e.required),ref:i},u),t,o.required&&s.createElement("span",{"aria-hidden":!0,className:c(e.asterisk,o.error&&e.error)}," ","*"))});const w=p(q,{name:"MuiFormLabel"})(C);export{w as F,v as f};
//# sourceMappingURL=FormLabel-678f11b5.js.map
