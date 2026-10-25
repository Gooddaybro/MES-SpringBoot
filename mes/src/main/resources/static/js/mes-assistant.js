/* V1 MES assistant widget. All server strings rendered as text; no arbitrary HTML. */
(function () {
  "use strict";
  var root=document.getElementById("mes-ai-widget");
  if(!root || !window.jQuery) return;
  var launcher=document.getElementById("mes-ai-launcher"),panel=document.getElementById("mes-ai-panel");
  var close=document.getElementById("mes-ai-close"),form=document.getElementById("mes-ai-form");
  var input=document.getElementById("mes-ai-input"),send=document.getElementById("mes-ai-send");
  var messages=document.getElementById("mes-ai-messages"),shortcuts=root.querySelectorAll("[data-question]");
  var busy=false;
  function openPanel(value){panel.hidden=!value;launcher.setAttribute("aria-expanded",String(value));if(value)input.focus();else launcher.focus();}
  function add(role,text,data){
    var el=document.createElement("div");el.className="mes-ai-message mes-ai-"+role;
    el.appendChild(document.createTextNode(text||""));
    if(data&&data.steps&&data.steps.length){
      var ol=document.createElement("ol");ol.className="mes-ai-steps";
      data.steps.forEach(function(s){var li=document.createElement("li");
        li.textContent=(s.name||s.code||"未命名工序")+(s.code&&s.code!==s.name?"（"+s.code+"）":"");ol.appendChild(li);});
      el.appendChild(ol);
    }
    if(data&&data.source){var meta=document.createElement("div");meta.className="mes-ai-source";
      meta.textContent="来源："+data.source;el.appendChild(meta);}
    messages.appendChild(el);messages.scrollTop=messages.scrollHeight;return el;
  }
  function loading(value){busy=value;input.disabled=value;send.disabled=value;
    Array.prototype.forEach.call(shortcuts,function(b){b.disabled=value;});}
  function ask(q){
    q=(q||"").trim();if(!q||busy||q.length>200)return;
    add("user",q);input.value="";loading(true);
    var pending=add("bot","正在查询…");
    window.jQuery.ajax({url:root.getAttribute("data-api"),type:"POST",dataType:"json",
      data:{message:q},timeout:15000})
    .done(function(r){pending.parentNode.removeChild(pending);
      if(r&&r.code===0&&r.data)add("bot",r.data.answer,r.data);
      else add("bot",(r&&r.msg)||"查询未成功，请重试。");})
    .fail(function(){pending.parentNode.removeChild(pending);
      add("bot","服务不可用或登录已过期，请检查后端及登录状态；本助手不会猜测数据库结果。");})
    .always(function(){loading(false);if(!panel.hidden)input.focus();});
  }
  launcher.addEventListener("click",function(){openPanel(panel.hidden);});
  close.addEventListener("click",function(){openPanel(false);});
  form.addEventListener("submit",function(e){e.preventDefault();ask(input.value);});
  input.addEventListener("keydown",function(e){if(e.key==="Enter"&&!e.shiftKey&&!e.isComposing){e.preventDefault();ask(input.value);}});
  document.addEventListener("keydown",function(e){if(e.key==="Escape"&&!panel.hidden)openPanel(false);});
  Array.prototype.forEach.call(shortcuts,function(b){b.addEventListener("click",function(){ask(b.getAttribute("data-question"));});});
})();