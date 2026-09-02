
(function(){
  function okVideo(u){
    if (!u || typeof u !== 'string') return false;
    if (u.indexOf('blob:') === 0) return false;
    if (u.indexOf('playwm') >= 0) return false;
    if (u.indexOf('_b_') >= 0 || u.indexOf('tt=b') >= 0) return false;
    if (u.indexOf('http') !== 0) return false;
    if (u.indexOf('media-audio') >= 0) return false;
    if (u.indexOf('.js') >= 0 && u.indexOf('mime_type=video_mp4') < 0) return false;
    // 带 logo 水印的播放流仍可作为兜底，优先逻辑在 setVideo/prefer 里处理
    return u.indexOf('douyinvod') >= 0
      || u.indexOf('bytevod') >= 0
      || u.indexOf('vlabvod') >= 0
      || u.indexOf('365yg.com') >= 0
      || u.indexOf('kwaicdn.com') >= 0
      || u.indexOf('photo-video') >= 0
      || (u.indexOf('.mp4') >= 0 && u.indexOf('yximgs.com') >= 0)
      || (u.indexOf('mime_type=video_mp4') >= 0 && u.indexOf('/video/') >= 0);
  }

  function okImage(u){
    if (!u || typeof u !== 'string') return false;
    if (u.indexOf('http') !== 0) return false;
    if (u.indexOf('avatar') >= 0 || u.indexOf('100x100') >= 0) return false;
    if (u.indexOf('aweme_comment') >= 0 || u.indexOf('sticker') >= 0) return false;
    if (u.indexOf('pcweb_cover') >= 0 || u.indexOf('noop.jpeg') >= 0) return false;
    if (u.indexOf('uhead') >= 0 || u.indexOf('emotion') >= 0) return false;
    if (u.indexOf('biz_tag=aweme_images') >= 0) return true;
    if (u.indexOf('tplv-dy-aweme-images') >= 0) return true;
    if (u.indexOf('aweme-images') >= 0) return true;
    if (u.indexOf('tos-cn-i-') >= 0 && u.indexOf('image-cut-tos') < 0) return true;
    // 快手图集：排除 atlas 配乐 .m4a
    var path = (u.split('?')[0] || '').toLowerCase();
    if (/\.(m4a|mp3|aac|mp4|mov|wav|m3u8)$/.test(path)) return false;
    var isRaster = /\.(jpe?g|png|webp|bmp|heic)$/.test(path);
    if (!isRaster) return false;
    if (u.indexOf('/ufile/atlas/') >= 0) return true;
    if ((u.indexOf('yximgs.com') >= 0 || u.indexOf('kwimgs.com') >= 0) && u.indexOf('/ufile/') >= 0) {
      return true;
    }
    return false;
  }

  var out = {video:'', cover:'', title:'', source:'', images:[], awemeId:''};
  if (window.__dyCapture) {
    out.video = window.__dyCapture.video || '';
    out.cover = window.__dyCapture.cover || '';
    out.title = window.__dyCapture.title || '';
    out.source = window.__dyCapture.source || '';
    out.awemeId = window.__dyCapture.awemeId || '';
    if (window.__dyCapture.images && window.__dyCapture.images.length) {
      out.images = window.__dyCapture.images.slice();
    }
  }

  if (!okVideo(out.video)) {
    out.video = '';
    try {
      var entries = performance.getEntriesByType('resource');
      for (var i = 0; i < entries.length; i++) {
        var n = entries[i].name || '';
        if (!okVideo(n)) continue;
        if (n.indexOf('media-video') >= 0 || n.indexOf('photo-video') >= 0) {
          if (!out.video || n.indexOf('photo-video') >= 0) out.video = n;
          continue;
        }
        out.video = n;
        out.source = out.source || 'resource';
        break;
      }
    } catch (e) {}
  }

  if (!out.cover) {
    try {
      var metas = document.querySelectorAll('meta[property="og:image"], meta[name="og:image"]');
      for (var m = 0; m < metas.length; m++) {
        var c = metas[m].getAttribute('content') || '';
        if (c.indexOf('http') === 0) { out.cover = c; break; }
      }
    } catch (e) {}
  }
  if (!out.cover) {
    try {
      var imgs = document.querySelectorAll('img');
      for (var j = 0; j < imgs.length; j++) {
        var s = imgs[j].src || '';
        if (s.indexOf('douyinpic') >= 0) {
          if (s.indexOf('avatar') >= 0 || s.indexOf('100x100') >= 0) continue;
          if (s.indexOf('biz_tag=aweme_comment') >= 0) continue;
          if (s.indexOf('cover') >= 0 || s.indexOf('origin_cover') >= 0 || s.indexOf('tplv') >= 0) {
            out.cover = s;
            break;
          }
        }
        if (s.indexOf('yximgs.com') >= 0 || s.indexOf('kwimgs.com') >= 0) {
          if (s.indexOf('_b_') >= 0 || s.indexOf('uhead') >= 0) continue;
          if (s.indexOf('upic') >= 0 || s.indexOf('clientCacheKey') >= 0) {
            out.cover = s;
            break;
          }
        }
      }
    } catch (e) {}
  }

  if (!out.title) out.title = document.title || '';
  if (!okVideo(out.video)) out.video = '';

  function pushImage(u){
    if (!okImage(u)) return;
    // 去掉签名后比较，避免同图不同 CDN 重复
    var key = u.split('?')[0];
    for (var pi = 0; pi < out.images.length; pi++) {
      if (out.images[pi].split('?')[0] === key) return;
    }
    out.images.push(u);
  }

  // 若已从 detail 接口拿到完整列表，优先保留
  var fromDetail = out.source.indexOf('detail') >= 0 && out.images.length > 0;

  try {
    var domImgs = document.querySelectorAll('img');
    for (var di = 0; di < domImgs.length; di++) {
      pushImage(domImgs[di].src || '');
      var srcset = domImgs[di].currentSrc || '';
      if (srcset) pushImage(srcset);
    }
  } catch (e) {}

  try {
    var resEntries = performance.getEntriesByType('resource');
    for (var ri = 0; ri < resEntries.length; ri++) {
      pushImage(resEntries[ri].name || '');
    }
  } catch (e) {}

  // 尝试点一下轮播下一张，触发懒加载（不阻塞）
  try {
    if (!fromDetail) {
      var next = document.querySelector(
        'button[class*="arrow"], div[class*="arrow-right"], div[class*="swiper-button-next"], [class*="next"]'
      );
      if (next && typeof next.click === 'function') next.click();
    }
  } catch (e) {}

  if (!out.cover && out.images.length) out.cover = out.images[0];
  return JSON.stringify(out);
})();
