
(function(){
  if (window.__ksHooked) return;
  window.__ksHooked = true;
  if (!window.__dyCapture) {
    window.__dyCapture = {video:'', cover:'', title:'', source:'', images:[]};
  }
  if (!window.__dyCapture.images) window.__dyCapture.images = [];

  function okMp4(u){
    if (!u || typeof u !== 'string') return false;
    if (u.indexOf('http') !== 0) return false;
    if (u.indexOf('_b_') >= 0 || u.indexOf('tt=b') >= 0) return false;
    if (u.indexOf('watermark') >= 0) return false;
    return u.indexOf('.mp4') >= 0
      || u.indexOf('photo-video') >= 0
      || (u.indexOf('kwaicdn.com') >= 0 && u.indexOf('.mp4') >= 0);
  }

  function isRaster(u){
    var p = (u.split('?')[0] || '').toLowerCase();
    if (/\.(m4a|mp3|aac|mp4|mov|wav|m3u8)$/.test(p)) return false;
    return /\.(jpe?g|png|webp|bmp|heic)$/.test(p);
  }

  function okAtlas(u){
    if (!u || typeof u !== 'string') return false;
    if (u.indexOf('http') !== 0) return false;
    if (u.indexOf('uhead') >= 0 || u.indexOf('emotion') >= 0) return false;
    if (u.indexOf('avatar') >= 0) return false;
    // atlas 目录下常有 .m4a 配乐，必须带图片后缀
    if (!isRaster(u)) return false;
    return u.indexOf('/ufile/atlas/') >= 0
      || ((u.indexOf('yximgs.com') >= 0 || u.indexOf('kwimgs.com') >= 0) && u.indexOf('/ufile/') >= 0);
  }

  function pushAtlas(u){
    if (!okAtlas(u)) return;
    var key = u.split('?')[0];
    var list = window.__dyCapture.images;
    for (var i = 0; i < list.length; i++) {
      if (list[i].split('?')[0] === key) return;
    }
    list.push(u);
    window.__dyCapture.source = window.__dyCapture.source || 'atlas';
    if (!window.__dyCapture.cover) window.__dyCapture.cover = u;
  }

  function grabKs(text){
    try {
      if (!text) return;
      var j = JSON.parse(text);
      var detail = j.data && j.data.visionVideoDetail;
      if (detail && detail.photo) {
        var p = detail.photo;
        if (p.photoUrl && okMp4(p.photoUrl)) {
          window.__dyCapture.video = p.photoUrl;
          window.__dyCapture.source = 'graphql';
        }
        if (p.coverUrl) window.__dyCapture.cover = p.coverUrl;
        if (p.caption) window.__dyCapture.title = p.caption;
      }
      // 图集常见字段
      try {
        var walk = function(node, depth){
          if (!node || depth > 8) return;
          if (Array.isArray(node)) {
            for (var ai = 0; ai < node.length; ai++) walk(node[ai], depth + 1);
            return;
          }
          if (typeof node !== 'object') return;
          var keys = ['atlas', 'imageUrls', 'imageUrlList', 'imgUrls', 'urls', 'atlasList', 'photoList'];
          for (var ki = 0; ki < keys.length; ki++) {
            var arr = node[keys[ki]];
            if (!arr) continue;
            if (typeof arr === 'string') pushAtlas(arr);
            else if (Array.isArray(arr)) {
              for (var j = 0; j < arr.length; j++) {
                var it = arr[j];
                if (typeof it === 'string') pushAtlas(it);
                else if (it && typeof it === 'object') {
                  pushAtlas(it.url || it.src || it.cdnUrl || it.imageUrl || '');
                }
              }
            }
          }
          for (var k in node) {
            if (!Object.prototype.hasOwnProperty.call(node, k)) continue;
            if (typeof node[k] === 'object') walk(node[k], depth + 1);
          }
        };
        walk(j, 0);
      } catch (e) {}
      // 图集图片（排除 .m4a）
      var reImg = /https:\/\/[^"\\]+\/ufile\/atlas\/[^"\\]+\.(?:jpe?g|png|webp|bmp)[^"\\]*/gi;
      var m;
      while ((m = reImg.exec(text)) !== null) pushAtlas(m[0]);
      if (!window.__dyCapture.video) {
        var re = /https:\/\/[^"\\]+\.mp4[^"\\]*/g;
        while ((m = re.exec(text)) !== null) {
          if (okMp4(m[0])) {
            window.__dyCapture.video = m[0];
            window.__dyCapture.source = 'json';
            break;
          }
        }
      }
    } catch (e) {}
  }

  function pollMedia(){
    try {
      var v = document.querySelector('video');
      if (v) {
        var src = v.currentSrc || v.src || '';
        if (okMp4(src)) {
          window.__dyCapture.video = src;
          window.__dyCapture.source = window.__dyCapture.source || 'video';
        }
      }
      var imgs = document.querySelectorAll('img');
      for (var i = 0; i < imgs.length; i++) {
        pushAtlas(imgs[i].src || '');
        pushAtlas(imgs[i].currentSrc || '');
      }
      try {
        var entries = performance.getEntriesByType('resource');
        for (var r = 0; r < entries.length; r++) pushAtlas(entries[r].name || '');
      } catch (e) {}
      if (!window.__dyCapture.title) {
        var t = (document.title || '').replace(/\s*-\s*快手\s*$/, '').trim();
        if (t && t !== '快手') window.__dyCapture.title = t;
      }
    } catch (e) {}
  }
  setInterval(pollMedia, 400);
  pollMedia();

  try {
    var ofetch = window.fetch;
    window.fetch = function(){
      var args = arguments;
      return ofetch.apply(this, args).then(function(res){
        try {
          var req = args[0];
          var u = (typeof req === 'string') ? req : (req && req.url) || '';
          if (u && (u.indexOf('graphql') >= 0 || u.indexOf('photo') >= 0 || u.indexOf('rest') >= 0)) {
            res.clone().text().then(grabKs);
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
      this.__ksUrl = url;
      return open.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function(){
      this.addEventListener('load', function(){
        try {
          var u = String(this.__ksUrl || '');
          if (u.indexOf('graphql') >= 0 || u.indexOf('photo') >= 0 || u.indexOf('rest') >= 0) {
            grabKs(this.responseText);
          }
        } catch (e) {}
      });
      return send.apply(this, arguments);
    };
  } catch (e) {}
})();
