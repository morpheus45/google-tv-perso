"use strict";

// ─── Item courant ──────────────────────────────────────────────────────────────
const item = JSON.parse(sessionStorage.getItem("iptv_current_item") || "null");

function $(id){ return document.getElementById(id); }

// ─── Détection appareil ────────────────────────────────────────────────────────
const UA        = navigator.userAgent;
const isAndroid = /Android/i.test(UA);
const isTV      = /TV|GoogleTV|SmartTV|AndroidTV/i.test(UA) ||
                  (isAndroid && !UA.includes("Mobile")) ||
                  window.PIPSILY_NATIVE === "android_tv";
const isNative  = typeof window.AndroidBridge !== "undefined";

// ─── Helpers URL ───────────────────────────────────────────────────────────────
function getExtension(url){
  if(!url) return "";
  try { return new URL(url).pathname.split(".").pop().toLowerCase().split("?")[0]; }
  catch { return url.split("?")[0].split(".").pop().toLowerCase(); }
}
function isHls(url){ return url.includes(".m3u8") || url.includes("/hls/"); }
function isMpegTs(url){ return getExtension(url) === "ts" || url.includes(".ts?"); }
function isBrowserUnfriendly(url){
  return ["mkv","avi","wmv","flv","mov"].includes(getExtension(url));
}
function isHttpUrl(url){ return /^http:/i.test(url); }
function toHttp(url){ return url.replace(/^https?:\/\//i, "http://"); }
function formatTime(s){
  if(!isFinite(s)||s<0) return "0:00";
  const m=Math.floor(s/60), sec=Math.floor(s%60);
  return `${m}:${String(sec).padStart(2,"0")}`;
}

// ─── Résoudre l'URL ────────────────────────────────────────────────────────────
function resolveUrl(){
  if(!item) return null;
  if(item.selected_episode) return item.selected_episode.stream_url || item.selected_episode.url || null;
  return item.stream_url || item.url || null;
}

// ─── Instances lecteurs ────────────────────────────────────────────────────────
let hlsInst=null, mpegtsInst=null;
function destroyPlayers(){
  if(hlsInst)   { try{hlsInst.destroy();}   catch{} hlsInst=null; }
  if(mpegtsInst){ try{mpegtsInst.destroy();} catch{} mpegtsInst=null; }
  const v=$("video");
  if(v){ v.pause(); v.removeAttribute("src"); v.load(); }
}

// ─── Overlay & statut ─────────────────────────────────────────────────────────
function setStatus(msg,type){
  const n=$("playbackStatus"); if(!n) return;
  n.hidden=!msg; n.textContent=msg||"";
  n.className="playback-status"+(type==="error"?" playback-status--error":"");
}
function showOverlay(){ const o=$("overlay"); if(o) o.style.display=""; }
function hideOverlay(){ const o=$("overlay"); if(o) o.style.display="none"; }

// ─── Contrôles TV superposés ───────────────────────────────────────────────────
let ctrlTimeout=null;
function showControls(){
  const c=$("tvControls"); if(!c) return;
  c.classList.add("visible");
  clearTimeout(ctrlTimeout);
  ctrlTimeout=setTimeout(()=>c.classList.remove("visible"), 4000);
}
function hideControls(){
  clearTimeout(ctrlTimeout);
  const c=$("tvControls"); if(c) c.classList.remove("visible");
}

// ─── Seekbar TV ───────────────────────────────────────────────────────────────
function updateSeek(){
  const v=$("video"); if(!v||!v.duration) return;
  const pct=(v.currentTime/v.duration)*100;
  const f=$("tc-fill"); if(f) f.style.width=pct+"%";
  const cur=$("tc-cur"); if(cur) cur.textContent=formatTime(v.currentTime);
  const dur=$("tc-dur"); if(dur) dur.textContent=formatTime(v.duration);
}
setInterval(updateSeek, 500);

// ─── Navigation épisodes ───────────────────────────────────────────────────────
function getEpList(){ return Array.isArray(item?.all_episodes)?item.all_episodes:[]; }
function getCurIdx(){ return typeof item?.current_ep_index==="number"?item.current_ep_index:-1; }

function updateNavButtons(){
  const list=getEpList(), idx=getCurIdx();
  const p=$("prevEpBtn"), n=$("nextEpBtn");
  const tp=$("tc-prev"),  tn=$("tc-next");
  const hasPrev=idx>0&&list.length>0;
  const hasNext=idx>=0&&idx<list.length-1;
  if(p) p.disabled=!hasPrev;
  if(n) n.disabled=!hasNext;
  if(tp) tp.disabled=!hasPrev;
  if(tn) tn.disabled=!hasNext;
}

function goEpisode(newIdx){
  const list=getEpList();
  if(newIdx<0||newIdx>=list.length) return;
  const ep=list[newIdx];
  if(!ep||!ep.url) return;
  const updated={...item,
    episode_label:`S${String(ep.season).padStart(2,"0")}E${String(ep.episode_num).padStart(2,"0")}`,
    episode_title:ep.title, stream_url:ep.url, url:ep.url,
    progress_key:ep.progress_key, plot:ep.plot||item.plot||"",
    selected_episode:{...ep,stream_url:ep.url}, current_ep_index:newIdx};
  sessionStorage.setItem("iptv_current_item",JSON.stringify(updated));
  location.reload();
}
function goPrev(){ goEpisode(getCurIdx()-1); }
function goNext(){ goEpisode(getCurIdx()+1); }

// ─── Retour vers l'app ─────────────────────────────────────────────────────────
function goBackToApp(){
  // Sauvegarde progression (méthode VOD : pf_last_progress → lu par init() dans index.html)
  try {
    const v = document.getElementById("video");
    const key = resolveUrl();
    if (v && key && v.duration > 0) {
      const pct = Math.round(v.currentTime / v.duration * 100);
      sessionStorage.setItem('pf_last_progress', JSON.stringify({key, pct, item}));
    }
  } catch {}
  if(history.length>1) history.back();
  else window.location.href="index.html";
}

// ─── Lecteur natif / VLC ───────────────────────────────────────────────────────
//
//  goldenlink.live est HTTP uniquement.
//  Sur Android TV APK : on ouvre VLC embarqué via AndroidBridge.openInVlc()
//  puis on revient à cette page quand l'utilisateur appuie sur Retour dans VLC.
//
function openExternalPlayer(rawUrl){
  const httpUrl=toHttp(rawUrl);
  const title=item?.title||"";
  const isLive=item?.isLive||false;

  const video=$("video");
  if(video) video.style.display="none";
  hideOverlay();
  setStatus("");

  // APK Android TV → VLC embarqué (openInVlc) ou lecteur système (openVideo)
  if(isNative){
    if(window.AndroidBridge?.openInVlc){
      try{ window.AndroidBridge.openInVlc(httpUrl, title, isLive); }catch(e){}
      // Pas de history.back() ici : VlcPlayerActivity retourne automatiquement
      // à cette WebView quand l'utilisateur appuie sur Retour dans VLC
    } else if(window.AndroidBridge?.openVideo){
      try{ window.AndroidBridge.openVideo(httpUrl, title); }catch(e){}
    }
    return;
  }

  // Navigateur sans APK → intent VLC ou écran d'aide
  window.location.href=
    `intent:${httpUrl}#Intent;action=android.intent.action.VIEW;type=video/*;`+
    `package=org.videolan.vlc;end`;
}

// ─── Gestion d'erreur vidéo ────────────────────────────────────────────────────
function handleVideoError(url, rawUrl){
  if(isAndroid||isTV){ openExternalPlayer(rawUrl||url); return; }
  const v=$("video");
  const err=v?.error;
  let msg="Erreur de lecture.";
  if(err) switch(err.code){
    case 2: msg="Erreur réseau."; break;
    case 3: msg="Erreur de décodage."; break;
    case 4: msg=isBrowserUnfriendly(url)?
      `Format ${getExtension(url).toUpperCase()} non supporté.` :
      "Format non supporté."; break;
  }
  setStatus(msg,"error"); showOverlay();
}

// ─── Stratégies de lecture (desktop / HTTPS) ──────────────────────────────────
function playHls(video, url){
  if(typeof Hls!=="undefined"&&Hls.isSupported()){
    hlsInst=new Hls({enableWorker:true,lowLatencyMode:true});
    hlsInst.loadSource(url); hlsInst.attachMedia(video);
    hlsInst.on(Hls.Events.MANIFEST_PARSED,()=>{ setStatus(""); video.play().catch(()=>{}); });
    hlsInst.on(Hls.Events.ERROR,(_,d)=>{
      if(d.fatal){
        if(d.type===Hls.ErrorTypes.NETWORK_ERROR) hlsInst.startLoad();
        else if(d.type===Hls.ErrorTypes.MEDIA_ERROR) hlsInst.recoverMediaError();
        else { setStatus("Erreur HLS.","error"); showOverlay(); }
      }
    });
  } else if(video.canPlayType("application/vnd.apple.mpegurl")){
    video.src=url; video.play().catch(()=>{});
  } else { setStatus("HLS non supporté.","error"); showOverlay(); }
}

function playMpegTs(video, url){
  if(typeof mpegts!=="undefined"&&mpegts.getFeatureList().mseLivePlayback){
    mpegtsInst=mpegts.createPlayer({type:"mse",url,enableWorker:true});
    mpegtsInst.attachMediaElement(video); mpegtsInst.load();
    mpegtsInst.play().catch(()=>{ video.src=url; video.play().catch(()=>{}); });
  } else { video.src=url; video.play().catch(()=>{}); }
}

function playNative(video, url){
  video.src=url;
  video.play().catch(()=>{
    setStatus(isBrowserUnfriendly(url)?
      `Format ${getExtension(url).toUpperCase()} non supporté.` :
      "Impossible de lire ce flux.","error");
  });
}

// ─── Init ──────────────────────────────────────────────────────────────────────
function initPlayer(){
  if(!item){ setStatus("Aucun média sélectionné.","error"); return; }

  const rawUrl=resolveUrl();
  if(!rawUrl){ setStatus("URL introuvable.","error"); return; }

  // Titre
  const label=item.episode_label
    ? `${item.title} — ${item.episode_label}` : item.title||"Lecture";
  const sub=item.episode_title||item.category_name||item.category||"";
  if($("playerTitle")) $("playerTitle").textContent=label;
  if($("playerSub"))   $("playerSub").textContent=sub;
  if($("tc-title"))    $("tc-title").textContent=label;
  document.title=label+" — Google TV Perso";
  if($("plotText"))    $("plotText").textContent=item.plot||"";
  if($("liveIndicator")) $("liveIndicator").hidden=!item.isLive;
  const sr=$("seekRow"); if(sr) sr.hidden=!!item.isLive;

  updateNavButtons();

  // ══════════════════════════════════════════════════════════════════
  //  Android TV APK + URL HTTP → VLC embarqué IMMÉDIAT
  //  goldenlink.live ne supporte pas HTTPS, le mixed-content est bloqué.
  // ══════════════════════════════════════════════════════════════════
  if((isAndroid||isTV)&&isHttpUrl(rawUrl)){
    setStatus("Ouverture dans le lecteur vidéo…");
    showControls();
    setTimeout(()=>openExternalPlayer(rawUrl), 250);
    return;
  }

  // Desktop / HTTPS → lecture dans le player HTML5
  const video=$("video");
  if(!video) return;
  destroyPlayers();
  showOverlay(); setStatus("Chargement…");

  video.onplaying=()=>{ hideOverlay(); setStatus(""); showControls(); };
  video.onpause  =()=>{ if(!video.ended) showOverlay(); };
  video.onended  =()=>{ showOverlay(); setTimeout(goNext,3000); };
  video.onerror  =()=>handleVideoError(rawUrl, rawUrl);
  video.ontimeupdate=updateSeek;

  setTimeout(()=>{
    if(isHls(rawUrl))     playHls(video, rawUrl);
    else if(isMpegTs(rawUrl)) playMpegTs(video, rawUrl);
    else                      playNative(video, rawUrl);
  },150);
}

// ─── Bindings boutons ─────────────────────────────────────────────────────────
if($("backBtn"))       $("backBtn").onclick      = goBackToApp;
if($("tc-back"))       $("tc-back").onclick      = goBackToApp;
if($("prevEpBtn"))     $("prevEpBtn").onclick     = goPrev;
if($("nextEpBtn"))     $("nextEpBtn").onclick     = goNext;
if($("tc-prev"))       $("tc-prev").onclick       = goPrev;
if($("tc-next"))       $("tc-next").onclick       = goNext;
if($("tc-ff"))         $("tc-ff").onclick         = ()=>{ const v=$("video"); if(v) v.currentTime=Math.min(v.duration||Infinity,v.currentTime+10); showControls(); };
if($("tc-rw"))         $("tc-rw").onclick         = ()=>{ const v=$("video"); if(v) v.currentTime=Math.max(0,v.currentTime-10); showControls(); };
if($("tc-fs"))         $("tc-fs").onclick         = ()=>{ const v=$("video"); if(!v) return; document.fullscreenElement?document.exitFullscreen():(v.requestFullscreen||v.webkitRequestFullscreen)?.call(v); };
if($("tc-play"))       $("tc-play").onclick       = ()=>{ const v=$("video"); if(v) v.paused?v.play():v.pause(); };
if($("playOverlayBtn"))$("playOverlayBtn").onclick = ()=>{ const v=$("video"); if(v) v.paused?v.play():v.pause(); };

// Sync icône play/pause
const video=$("video");
if(video){
  video.addEventListener("play",  ()=>{ if($("tc-play")) $("tc-play").textContent="⏸"; });
  video.addEventListener("pause", ()=>{ if($("tc-play")) $("tc-play").textContent="▶"; });
}

// ─── Télécommande TV (CustomEvents depuis HomeActivity) ───────────────────────
window.addEventListener("tv_back",  ()=>goBackToApp());
window.addEventListener("tv_enter", ()=>{
  const v=$("video");
  if(!v) return;
  if(v.style.display==="none"){ /* VLC actif, rien à faire */ return; }
  showControls();
  v.paused?v.play():v.pause();
});
window.addEventListener("tv_right", ()=>{ const v=$("video"); if(v&&v.style.display!=="none"){ v.currentTime=Math.min(v.duration||Infinity,v.currentTime+10); showControls(); } });
window.addEventListener("tv_left",  ()=>{ const v=$("video"); if(v&&v.style.display!=="none"){ v.currentTime=Math.max(0,v.currentTime-10); showControls(); } });
window.addEventListener("tv_up",    ()=>{ const v=$("video"); if(v){ v.volume=Math.min(1,v.volume+0.1); showControls(); } });
window.addEventListener("tv_down",  ()=>{ const v=$("video"); if(v){ v.volume=Math.max(0,v.volume-0.1); showControls(); } });
window.addEventListener("tv_playpause",()=>{ const v=$("video"); if(v) v.paused?v.play():v.pause(); });
window.addEventListener("tv_menu",  ()=>showControls());

// ─── Clavier (dev / desktop) ──────────────────────────────────────────────────
document.addEventListener("keydown",e=>{
  const v=$("video");
  if(["Escape","GoBack","BrowserBack"].includes(e.key)){ e.preventDefault(); goBackToApp(); return; }
  if([" ","Enter","MediaPlayPause"].includes(e.key)){ e.preventDefault(); if(v) v.paused?v.play():v.pause(); showControls(); return; }
  if(e.key==="ArrowRight"){ if(v){ e.preventDefault(); v.currentTime=Math.min(v.duration||Infinity,v.currentTime+10); showControls(); } }
  if(e.key==="ArrowLeft" ){ if(v){ e.preventDefault(); v.currentTime=Math.max(0,v.currentTime-10); showControls(); } }
  if(e.key==="ArrowUp"   ){ if(v){ e.preventDefault(); v.volume=Math.min(1,v.volume+0.1); showControls(); } }
  if(e.key==="ArrowDown" ){ if(v){ e.preventDefault(); v.volume=Math.max(0,v.volume-0.1); showControls(); } }
  if(e.key==="f"||e.key==="F"){ $("tc-fs")?.click(); }
  if(e.key==="n"||e.key==="ChannelUp")  goNext();
  if(e.key==="p"||e.key==="ChannelDown")goPrev();
});

// ─── Démarrage ────────────────────────────────────────────────────────────────
initPlayer();
