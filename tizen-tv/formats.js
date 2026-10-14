(function () {
  'use strict';
  var groups = {
    audio: ['mp3','m4a','mp4a','aac','wav','wave','flac','ogg','oga','opus','wma','aiff','aif','amr','alac','ape','dsf','dff','mka','mid','midi'],
    video: ['mp4','m4v','mkv','avi','mov','mpg','mpeg','ts','m2ts','webm','3gp','vob','wmv','asf','flv','ogv','m2v','mts','hevc','h264','h265'],
    pdf: ['pdf'],
    text: ['txt','md','markdown','log','csv','tsv','json','xml','yaml','yml','ini','srt','vtt','ass','ssa','sub'],
    ebook: ['epub','fb2','mobi','azw','azw3','kfx','lit','pdb','djvu','djv'],
    office: ['doc','docx','odt','rtf','xls','xlsx','ods','ppt','pptx','odp'],
    comic: ['cbz','cbr','cb7','cbt'],
    image: ['jpg','jpeg','png','webp','gif','bmp','avif','heic','tif','tiff'],
    archive: ['zip','rar','7z','tar','gz','bz2','xz'],
    subtitle: ['srt','vtt','ass','ssa','sub']
  };
  var labels = {
    audio:'Audio', video:'Video', pdf:'PDF document', text:'Text / subtitle', ebook:'E-book',
    office:'Office document', comic:'Comic archive', image:'Image', archive:'Archive', subtitle:'Subtitle'
  };
  var icons = {audio:'♫',video:'▷',pdf:'▤',text:'≡',ebook:'▤',office:'▧',comic:'▥',image:'▧',archive:'▣',subtitle:'≡'};
  var lookup = Object.create(null);
  Object.keys(groups).forEach(function (kind) {
    groups[kind].forEach(function (ext) {
      if (!lookup[ext] || kind === 'subtitle') lookup[ext] = kind;
    });
  });
  function extension(name) {
    var clean = String(name || '').split(/[?#]/)[0];
    var dot = clean.lastIndexOf('.');
    return dot < 0 ? '' : clean.slice(dot + 1).toLowerCase();
  }
  function classify(name) { return lookup[extension(name)] || ''; }
  function label(kind) { return labels[kind] || 'File'; }
  function icon(kind) { return icons[kind] || '•'; }
  function isMedia(kind) { return kind === 'audio' || kind === 'video'; }
  function readerState(kind, ext) {
    if (kind === 'audio' || kind === 'video') return {state:'device-decoder', message:'The TV must decode the file container and its internal audio/video codecs.'};
    if (kind === 'pdf') return {state:'native-viewer-unverified', message:'PDF is recognized; embedded PDF viewing depends on the TV WebView.'};
    if ((kind === 'text' || kind === 'subtitle') && ['txt','md','markdown','log','csv','tsv','json','xml','yaml','yml','ini','srt','vtt','ass','ssa','sub'].indexOf(ext) >= 0) return {state:'text-reader', message:'Text can be read by the built-in text viewer.'};
    if (kind === 'image') return {state:'native-image-unverified', message:'Image decoding depends on the TV WebView.'};
    if (kind === 'ebook') return {state:'reader-engine-required', message:'A local EPUB/ebook rendering engine must be bundled before this format can be read.'};
    if (kind === 'comic') return {state:'archive-engine-required', message:'CBZ/CBR comic archive extraction and page rendering require a bundled archive engine.'};
    if (kind === 'office') return {state:'office-engine-required', message:'Word/Excel/PowerPoint files need a bundled document conversion/rendering engine.'};
    if (kind === 'archive') return {state:'archive-engine-required', message:'Archive extraction is not enabled for this format yet.'};
    if (kind === 'text') return {state:'text-reader', message:'This text-like file may be displayed as plain text; formatting may be lost.'};
    return {state:'recognized-only', message:'The file type is recognized but has no reader enabled.'};
  }
  window.MortimerFormats = {groups:groups, extension:extension, classify:classify, label:label, icon:icon, isMedia:isMedia, readerState:readerState};
})();