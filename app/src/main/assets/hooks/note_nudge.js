
(function(){
  try {
    var root = document.querySelector('#douyin-web-recommend-card-main')
      || document.querySelector('[class*="note"]')
      || document.querySelector('[class*="slider"]')
      || document.querySelector('[class*="swiper"]')
      || document.body;
    var imgs = document.querySelectorAll('img');
    for (var i = 0; i < Math.min(imgs.length, 12); i++) {
      try { imgs[i].scrollIntoView({block:'nearest', inline:'nearest'}); } catch (e) {}
    }
    // 模拟方向键 / 点击下一张，促发图集懒加载
    ['ArrowRight','ArrowRight','ArrowRight','ArrowRight'].forEach(function(k){
      try {
        document.dispatchEvent(new KeyboardEvent('keydown', {key:k, bubbles:true}));
      } catch (e) {}
    });
    var next = document.querySelector(
      '[class*="arrow-right"], [class*="swiper-button-next"], [class*="next"], button[aria-label*="下一"]'
    );
    if (next && typeof next.click === 'function') {
      try { next.click(); } catch (e) {}
    }
    // 横向滑动手势（快手 H5 轮播）
    try {
      var target = document.querySelector('[class*="swiper"], [class*="slider"], [class*="carousel"]') || root;
      if (target) {
        var r = target.getBoundingClientRect();
        var x = r.left + r.width * 0.75;
        var y = r.top + r.height * 0.5;
        var opts = {bubbles:true, cancelable:true, clientX:x, clientY:y};
        target.dispatchEvent(new TouchEvent('touchstart', opts));
        target.dispatchEvent(new TouchEvent('touchmove', {
          bubbles:true, cancelable:true,
          clientX: r.left + r.width * 0.2, clientY:y
        }));
        target.dispatchEvent(new TouchEvent('touchend', opts));
      }
    } catch (e) {}
  } catch (e) {}
})();
