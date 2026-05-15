package com.nuvio.tv.core.server

object DebridFormatterWebPage {
    fun html(): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Nuvio Direct Debrid Formatter</title>
<style>
body{margin:0;background:#0b0d12;color:#f4f7fb;font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
main{max-width:880px;margin:0 auto;padding:28px 18px 44px}
h1{font-size:28px;margin:0 0 8px}
p{color:#aab3c2;line-height:1.5}
label{display:block;margin:22px 0 8px;font-weight:700}
textarea{width:100%;min-height:150px;box-sizing:border-box;border:1px solid #2a3240;border-radius:10px;background:#121722;color:#f4f7fb;padding:14px;font:13px ui-monospace,SFMono-Regular,Menlo,monospace;line-height:1.45}
#descriptionTemplate{min-height:300px}
.row{display:flex;gap:10px;flex-wrap:wrap;margin-top:18px}
button{border:0;border-radius:999px;padding:12px 18px;background:#f4f7fb;color:#0b0d12;font-weight:800}
button.secondary{background:#202838;color:#f4f7fb}
.status{margin-top:14px;color:#87efac;min-height:24px}
.chips{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
.chip{border:1px solid #2a3240;border-radius:999px;padding:8px 10px;background:#121722;color:#cbd5e1;font-size:12px}
code{color:#e7eaf2}
</style>
</head>
<body>
<main>
<h1>Direct Debrid Formatter</h1>
<p>Customize the name and description used for <strong>Direct Debrid</strong> streams. Templates support conditional blocks, transforms, nested placeholders, joins, replacements, bytes, and time formatting.</p>
<div class="chips">
<span class="chip">{stream.resolution}</span>
<span class="chip">{stream.quality}</span>
<span class="chip">{stream.visualTags::join(' | ')}</span>
<span class="chip">{stream.audioTags::join(' | ')}</span>
<span class="chip">{stream.size::bytes}</span>
<span class="chip">{service.cached::istrue["Ready"||"Not Ready"]}</span>
</div>
<label for="nameTemplate">Name Template</label>
<textarea id="nameTemplate" spellcheck="false"></textarea>
<label for="descriptionTemplate">Description Template</label>
<textarea id="descriptionTemplate" spellcheck="false"></textarea>
<div class="row">
<button id="save">Save Formatter</button>
<button class="secondary" id="defaults">Restore Default</button>
</div>
<div class="status" id="status"></div>
</main>
<script>
let defaults = null;
const nameBox = document.getElementById('nameTemplate');
const descBox = document.getElementById('descriptionTemplate');
const statusBox = document.getElementById('status');
async function load(){
  const res = await fetch('/api/settings');
  const body = await res.json();
  defaults = body.defaults;
  nameBox.value = body.settings.nameTemplate || defaults.nameTemplate;
  descBox.value = body.settings.descriptionTemplate || defaults.descriptionTemplate;
}
async function save(){
  statusBox.textContent = 'Saving...';
  const res = await fetch('/api/settings',{
    method:'POST',
    headers:{'Content-Type':'application/json; charset=utf-8'},
    body:JSON.stringify({nameTemplate:nameBox.value,descriptionTemplate:descBox.value})
  });
  if(res.ok){
    statusBox.textContent = 'Saved. New streams will use this formatter.';
  }else{
    const body = await res.json().catch(()=>({error:'Could not save'}));
    statusBox.textContent = body.error || 'Could not save';
  }
}
document.getElementById('save').addEventListener('click',save);
document.getElementById('defaults').addEventListener('click',()=>{
  if(!defaults)return;
  nameBox.value = defaults.nameTemplate;
  descBox.value = defaults.descriptionTemplate;
});
load().catch(()=>{statusBox.textContent='Could not load formatter settings';});
</script>
</body>
</html>
""".trimIndent()
}
