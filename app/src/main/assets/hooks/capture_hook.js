
(function(){
  // 目标作品 ID：由原生在每次解析前注入。目标变化时必须清空旧 capture，
  // 否则会误用上一支视频 / 相关推荐里的 play_addr。
  // 注意：matchesTarget 必须每次读 window.__dyTargetId，不能闭包缓存。
  function currentTarget(){
    return String(window.__dyTargetId || '');
  }
  if (window.__dyHooked) {
    var tidNow = currentTarget();
    if (window.__dyCaptureTarget !== tidNow) {
      window.__dyCapture = {video:'', cover:'', title:'', source:'', images:[], awemeId:''};
      window.__dyCaptureTarget = tidNow;
    }
    return;
  }
  window.__dyHooked = true;
  window.__dyCaptureTarget = currentTarget();
  window.__dyCapture = {video:'', cover:'', title:'', source:'', images:[], awemeId:''};

  function awemeIdOf(d){
    if (!d || typeof d !== 'object') return '';
    // 不用裸 id：嵌套对象里经常有无关数字 id，容易误伤
    return String(d.aweme_id || d.awemeId || d.group_id || d.item_id || '');
  }

  function matchesTarget(aid){
    var tid = currentTarget();
    if (!tid) return true;
    if (!aid) return false;
    return String(aid) === tid;
  }

  function firstUrl(addr){
    try {
      if (!addr) return '';
      if (typeof addr === 'string') {
        if (addr.indexOf('http') === 0 && addr.indexOf('playwm') < 0 && addr.indexOf('blob:') !== 0) return addr;
        return '';
      }
      if (!addr.url_list || !addr.url_list.length) return '';
      var best = '';
      var bestScore = -9999;
      for (var i = 0; i < addr.url_list.length; i++) {
        var u = addr.url_list[i] || '';
        if (!u || u.indexOf('blob:') === 0 || u.indexOf('playwm') >= 0) continue;
        if (u.indexOf('http') !== 0) continue;
        var s = 0;
        if (u.indexOf('douyinvod') >= 0) s += 30;
        if (u.indexOf('/logo/') >= 0 || u.indexOf('/mps/logo') >= 0) s -= 80;
        if (u.indexOf('tos-cn-ve') >= 0) s += 15;
        if (s > bestScore) { bestScore = s; best = u; }
      }
      return best;
    } catch (e) { return ''; }
  }

  function preferVideo(u){
    if (!u) return false;
    if (u.indexOf('playwm') >= 0) return false;
    return u.indexOf('douyinvod') >= 0
      || u.indexOf('bytevod') >= 0
      || u.indexOf('vlabvod') >= 0
      || u.indexOf('365yg.com') >= 0
      || u.indexOf('mime_type=video_mp4') >= 0
      || (u.indexOf('.mp4') >= 0 && u.indexOf('http') === 0)
      || u.indexOf('.m3u8') >= 0;
  }

  function setVideo(u, source){
    if (!preferVideo(u)) return;
    var cur = window.__dyCapture.video || '';
    var score = function(x){
      var s = 0;
      if (x.indexOf('douyinvod') >= 0) s += 30;
      if (x.indexOf('bytevod') >= 0 || x.indexOf('vlabvod') >= 0) s += 25;
      if (x.indexOf('mime_type=video_mp4') >= 0) s += 20;
      if (x.indexOf('.mp4') >= 0) s += 10;
      if (x.indexOf('download') >= 0) s += 25;
      if (x.indexOf('tos-cn-ve') >= 0) s += 15;
      if (x.indexOf('/logo/') >= 0 || x.indexOf('/mps/logo') >= 0) s -= 80;
      if (x.indexOf('playwm') >= 0) s -= 100;
      return s;
    };
    if (!cur || score(u) > score(cur)) {
      window.__dyCapture.video = u;
      window.__dyCapture.source = source || window.__dyCapture.source || 'json';
    }
  }

  function takeAweme(d, source){
    if (!d || typeof d !== 'object') return;
    var aid = awemeIdOf(d);
    var tid = currentTarget();
    // 有目标 ID 时，只收当前作品，丢掉推荐/列表里的其它 aweme
    if (tid && aid && !matchesTarget(aid)) return;
    if (tid && !aid && source !== 'detail') return;

    if (aid) window.__dyCapture.awemeId = aid;
    if (d.desc) window.__dyCapture.title = d.desc;

    if (d.images && d.images.length) {
      var imgs = [];
      for (var k = 0; k < d.images.length; k++) {
        var it = d.images[k] || {};
        var iu = '';
        // 注意：download_url_list 常是「抖音号」水印图；url_list 末项通常无水印原图
        if (it.url_list && it.url_list.length) {
          iu = it.url_list[it.url_list.length - 1] || it.url_list[0];
        }
        if (!iu && it.download_url_list && it.download_url_list.length) {
          iu = it.download_url_list[it.download_url_list.length - 1] || it.download_url_list[0];
        }
        if (!iu) {
          iu = firstUrl(it.origin_url) || firstUrl(it) || firstUrl(it.display_image) || firstUrl(it.download_url) || '';
        }
        if (iu && iu.indexOf('http') === 0) imgs.push(iu);
      }
      if (imgs.length) {
        window.__dyCapture.images = imgs;
        window.__dyCapture.cover = imgs[0];
        window.__dyCapture.source = source || 'detail-images';
      }
    }

    var v = d.video || d;
    if (!v) return;
    var video = firstUrl(v.download_addr)
      || firstUrl(v.play_addr)
      || firstUrl(v.play_addr_h264)
      || firstUrl(v.play_addr_265)
      || firstUrl(v.download_addr_low)
      || '';
    var cover = firstUrl(v.origin_cover) || firstUrl(v.cover) || firstUrl(v.dynamic_cover) || '';
    if (video) setVideo(video, source || 'detail');
    if (cover) window.__dyCapture.cover = cover;
  }

  function walk(node, depth){
    if (!node || depth > 12) return;
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length; i++) walk(node[i], depth + 1);
      return;
    }
    if (typeof node !== 'object') return;

    // 常见抖音结构
    if (node.aweme_detail) takeAweme(node.aweme_detail, 'detail');
    if (node.aweme_list && node.aweme_list.length) {
      for (var a = 0; a < node.aweme_list.length; a++) takeAweme(node.aweme_list[a], 'list');
    }
    if (node.item_list && node.item_list.length) {
      for (var b = 0; b < node.item_list.length; b++) takeAweme(node.item_list[b], 'item');
    }
    if (node.aweme) takeAweme(node.aweme, 'aweme');
    if (node.video && (node.video.play_addr || node.video.download_addr)) {
      takeAweme(node, 'video-obj');
    }
    // 无作品 ID 的裸 play_addr：有目标时一律跳过，避免相关推荐污染
    if (node.play_addr || node.download_addr) {
      var aid = awemeIdOf(node);
      var tid = currentTarget();
      if (!tid || matchesTarget(aid)) {
        if (!tid || aid) {
          var vu = firstUrl(node.download_addr) || firstUrl(node.play_addr) || firstUrl(node.play_addr_h264) || '';
          if (vu) setVideo(vu, 'addr');
        }
      }
    }

    for (var k in node) {
      if (!Object.prototype.hasOwnProperty.call(node, k)) continue;
      var val = node[k];
      if (val && typeof val === 'object') walk(val, depth + 1);
    }
  }

  function grab(text){
    try {
      if (!text || text.length < 20) return;
      // 先尝试整段 JSON
      if (text.charAt(0) === '{' || text.charAt(0) === '[') {
        walk(JSON.parse(text), 0);
      }
    } catch (e) {}
    try {
      // 从任意文本里抠 CDN 直链
      var re = /https?:\\\/\\\/[^"'\\\s]+(?:douyinvod|bytevod|vlabvod|365yg)[^"'\\\s]*/g;
      var m;
      while ((m = re.exec(text))) {
        var u = m[0].replace(/\\\//g, '/').replace(/\\u002F/g, '/');
        setVideo(u, 'regex');
      }
      var re2 = /https?:\/\/[^"'\s]+(?:douyinvod|bytevod|vlabvod)[^"'\s]*/g;
      while ((m = re2.exec(text))) setVideo(m[0], 'regex2');
    } catch (e) {}
  }

  function scanDom(){
    try {
      var el = document.getElementById('RENDER_DATA')
        || document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__')
        || document.querySelector('script#RENDER_DATA');
      if (el && el.textContent) {
        var raw = el.textContent;
        try { raw = decodeURIComponent(raw); } catch (e) {}
        grab(raw);
      }
    } catch (e) {}
    try {
      var scripts = document.querySelectorAll('script');
      for (var i = 0; i < Math.min(scripts.length, 40); i++) {
        var t = scripts[i].textContent || '';
        if (t.indexOf('play_addr') >= 0 || t.indexOf('douyinvod') >= 0 || t.indexOf('aweme') >= 0) {
          grab(t);
        }
      }
    } catch (e) {}
    try {
      var vs = document.querySelectorAll('video');
      for (var j = 0; j < vs.length; j++) {
        var src = vs[j].currentSrc || vs[j].src || '';
        if (src) setVideo(src, 'video-el');
        // 触发加载
        try { vs[j].muted = true; vs[j].playsInline = true; vs[j].play().catch(function(){}); } catch (e) {}
      }
    } catch (e) {}
    try {
      if (!window.__dyCapture.title) {
        var title = document.title || '';
        title = title.replace(/\s*-\s*抖音\s*$/, '').trim();
        if (title) window.__dyCapture.title = title;
      }
    } catch (e) {}
  }

  function nudgePlay(){
    try {
      var selectors = [
        'video',
        '[class*="play"]',
        '[class*="Play"]',
        'xg-icon-play',
        '.xgplayer-start',
        'button'
      ];
      for (var i = 0; i < selectors.length; i++) {
        var nodes = document.querySelectorAll(selectors[i]);
        for (var j = 0; j < Math.min(nodes.length, 3); j++) {
          var n = nodes[j];
          if (n && n.tagName === 'VIDEO') {
            try { n.muted = true; n.play().catch(function(){}); } catch (e) {}
          } else if (n && typeof n.click === 'function') {
            try { n.click(); } catch (e) {}
          }
        }
      }
    } catch (e) {}
  }

  try {
    var ofetch = window.fetch;
    window.fetch = function(){
      var args = arguments;
      return ofetch.apply(this, args).then(function(res){
        try {
          var req = args[0];
          var u = (typeof req === 'string') ? req : (req && req.url) || '';
          if (u && (u.indexOf('aweme') >= 0 || u.indexOf('iteminfo') >= 0 || u.indexOf('detail') >= 0)) {
            res.clone().text().then(function(t){ grab(t); });
          }
        } catch (e) {}
        return res;
      });
    };
  } catch (e) {}

  try {
    var open = XMLHttpRequest.prototype.open;
    var send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url){
      this.__dyUrl = url;
      return open.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function(){
      this.addEventListener('load', function(){
        try {
          var u = String(this.__dyUrl || '');
          if (u.indexOf('aweme') >= 0 || u.indexOf('iteminfo') >= 0 || u.indexOf('detail') >= 0) {
            grab(this.responseText);
          }
        } catch (e) {}
      });
      return send.apply(this, arguments);
    };
  } catch (e) {}

  // 立即扫一次 + 周期性扫描 / 点播放
  scanDom();
  nudgePlay();
  setInterval(function(){
    scanDom();
    nudgePlay();
  }, 900);
})();
