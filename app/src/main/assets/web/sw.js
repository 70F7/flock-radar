const SHELL="fr-shell-v1",DATA="fr-data-v1";
const SHELL_URLS=["/","/manifest.webmanifest","/vendor/leaflet.css","/vendor/leaflet.js"];
self.addEventListener("install",e=>{e.waitUntil(caches.open(SHELL).then(c=>c.addAll(SHELL_URLS)).then(()=>self.skipWaiting()));});
self.addEventListener("activate",e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>![SHELL,DATA].includes(x)).map(x=>caches.delete(x)))).then(()=>self.clients.claim()));});
self.addEventListener("fetch",e=>{const u=new URL(e.request.url);
  if(u.pathname.startsWith("/api/detect")||u.pathname.startsWith("/api/status")||u.pathname.startsWith("/api/download-area"))return;
  if(u.pathname.startsWith("/tiles/")||u.pathname.startsWith("/api/cameras")){
    e.respondWith(caches.open(DATA).then(async c=>{const h=await c.match(e.request);if(h)return h;
      try{const r=await fetch(e.request);if(r.ok)c.put(e.request,r.clone());return r;}catch(_){return h||new Response("",{status:504});}}));return;}
  e.respondWith(caches.match(e.request).then(h=>h||fetch(e.request).catch(()=>caches.match("/"))));});
