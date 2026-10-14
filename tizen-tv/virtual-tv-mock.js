(function () {
  'use strict';
  var files = {
    'music/': ['Sample Track.mp3','Lossless Demo.flac','Readme.txt'],
    'videos/': ['Sample Film.mp4','Unsupported Sample.mkv'],
    'documents/': ['Demo Book.epub','Demo Comic.cbz','Notes.txt','Manual.pdf'],
    'downloads/': ['Download Photo.jpg'],
    'pictures/': ['Gallery Demo.png'],
    'removable_sda1/': ['Music','Videos','Books','Comics','Photos','Documents'],
    'removable_sda1/Music/': ['USB Track.mp3','Album FLAC.flac'],
    'removable_sda1/Videos/': ['USB Film.mp4'],
    'removable_sda1/Books/': ['Demo Book.epub','Notes.txt'],
    'removable_sda1/Comics/': ['Demo Comic.cbz'],
    'removable_sda1/Photos/': ['Landscape.jpg','Portrait.png','Artwork.webp'],
    'removable_sda1/Documents/': ['Manual.pdf','Notes.txt']
  };
  var dirs = Object.create(null);
  Object.keys(files).forEach(function (p) { dirs[p.replace(/\/$/,'')] = true; });
  var content = {
    'music/Readme.txt':'Virtual TV test file. Text reading is simulated.',
    'documents/Notes.txt':'MUGEN Virtual TV\nThis text file is generated for testing the built-in reader.',
    'removable_sda1/Documents/Notes.txt':'Virtual USB text sample.'
  };
  function normalize(p) { return String(p || '').replace(/^\/+|\/+$/g,''); }
  function names(path) { return (files[normalize(path) + '/'] || []).slice(); }
  function uri(path) {
    var p = normalize(path), ext = p.split('.').pop().toLowerCase();
    if (['jpg','jpeg','png','webp','gif','bmp','tif','tiff','avif','heic'].indexOf(ext) >= 0) {
      var hue = p.indexOf('Portrait') >= 0 ? '#b67b9a' : (p.indexOf('Artwork') >= 0 ? '#d6ff45' : '#6d9dbe');
      var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="420"><defs><linearGradient id="g" x2="1" y2="1"><stop stop-color="'+hue+'"/><stop offset="1" stop-color="#10151d"/></linearGradient></defs><rect width="640" height="420" fill="url(#g)"/><circle cx="450" cy="100" r="55" fill="#f5e8b0"/><path d="M0 360 L190 170 320 300 450 200 640 360 V420 H0Z" fill="#152b2a" opacity=".9"/><text x="28" y="390" fill="white" font-size="20">MUGEN VIRTUAL TV</text></svg>';
      return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
    }
    return 'file:///virtual-tv/' + encodeURIComponent(p).replace(/%2F/g,'/');
  }
  function entry(path) {
    var p = normalize(path);
    return {
      fullPath: p,
      fileSize: (content[p] || '').length,
      openStream: function (mode, ok) {
        var value = content[p] || 'Virtual TV sample content for ' + p;
        ok({read:function(){return value;},close:function(){}});
      },
      copyTo: function (dest,name,overwrite,ok,fail) {
        var d=normalize(dest), target=d+'/'+name;
        if (!dirs[d]) return fail({message:'Destination folder does not exist in virtual storage.'});
        if (files[d+'/'] && files[d+'/'].indexOf(name)>=0 && !overwrite) return fail({message:'Destination already exists.'});
        files[d+'/']=files[d+'/']||[]; files[d+'/'].push(name);
        if (dirs[p]) files[target+'/']=files[p+'/'].slice(); else content[target]=content[p]||'Copied virtual file';
        ok();
      },
      moveTo: function (dest,name,overwrite,ok,fail) {
        var self=this;
        self.copyTo(dest,name,overwrite,function(){
          if(dirs[p]) {delete files[p+'/']; delete dirs[p];}
          else {var parent=p.slice(0,p.lastIndexOf('/'));files[parent+'/']=(files[parent+'/']||[]).filter(function(n){return n!==p.split('/').pop();});delete content[p];}
          ok();
        },fail);
      }
    };
  }
  var avState='IDLE', current=0, duration=185000, listener=null;
  window.__MUGEN_VIRTUAL_TV__ = {files:files, tests:[], mode:'mock', logs:[]};
  window.tizen = {
    filesystem: {
      listStorages:function(ok){setTimeout(function(){ok([{label:'removable_sda1',name:'Virtual USB Drive',type:'EXTERNAL',state:'MOUNTED'}]);},15);},
      listDirectory:function(path,ok,fail){setTimeout(function(){var p=normalize(path);if(files[p+'/'])ok(names(p));else fail({message:'Folder not found in virtual filesystem: '+p});},10);},
      isDirectory:function(path){return !!dirs[normalize(path)];},
      toURI:uri,
      resolve:function(path,ok,fail){var p=normalize(path);var parent=p.slice(0,p.lastIndexOf('/')), name=p.split('/').pop();
        if(dirs[p] || Object.keys(content).indexOf(p)>=0 || Object.keys(files).some(function(k){return k.replace(/\/$/,'')===p;}) || (files[parent+'/']||[]).indexOf(name)>=0){ok(entry(p));}else fail({message:'Virtual file not found: '+p});},
      deleteFile:function(path,ok){var p=normalize(path),parent=p.slice(0,p.lastIndexOf('/'));files[parent+'/']=(files[parent+'/']||[]).filter(function(n){return n!==p.split('/').pop();});delete content[p];ok();},
      deleteDirectory:function(path,recursive,ok){var p=normalize(path);delete files[p+'/'];delete dirs[p];ok();}
    },
    tvinputdevice:{registerKeyBatch:function(){}}
  };
  window.webapis = {avplay:{
    open:function(uri){avState='OPEN';current=0;window.__MUGEN_VIRTUAL_TV__.logs.push('AVPlay open: '+uri);},
    setListener:function(l){listener=l;},
    prepareAsync:function(ok){setTimeout(function(){avState='READY';ok();if(listener&&listener.onbufferingcomplete)listener.onbufferingcomplete();},30);},
    play:function(){avState='PLAYING';window.__MUGEN_VIRTUAL_TV__.logs.push('AVPlay play');},
    pause:function(){avState='PAUSED';},
    stop:function(){avState='IDLE';},
    close:function(){avState='IDLE';},
    getState:function(){return avState;},
    getCurrentTime:function(){return current;},
    getDuration:function(){return duration;},
    seekTo:function(ms,ok){current=Math.max(0,Math.min(duration,ms));if(ok)ok();},
    setDisplayRect:function(x,y,w,h){window.__MUGEN_VIRTUAL_TV__.logs.push('Display rect '+[x,y,w,h].join(','));}
  }};
})();