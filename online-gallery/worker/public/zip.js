// ZIP sem compressão (JPEG já é comprimido), montado no navegador. Nomes em UTF-8.
// Uso: LumoZip.build([{ name: "foto.jpg", data: Uint8Array, date: Date }]) -> Blob
(function () {
  "use strict";
  var TABLE = (function () {
    var t = new Uint32Array(256);
    for (var n = 0; n < 256; n++) {
      var c = n;
      for (var k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c >>> 0;
    }
    return t;
  })();

  function crc32(bytes) {
    var c = 0xffffffff;
    for (var i = 0; i < bytes.length; i++) c = TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  }

  function dosTime(d) {
    return ((d.getHours() << 11) | (d.getMinutes() << 5) | Math.floor(d.getSeconds() / 2)) & 0xffff;
  }

  function dosDate(d) {
    var y = Math.max(1980, d.getFullYear());
    return (((y - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate()) & 0xffff;
  }

  function uniqueNames(files) {
    var seen = {};
    return files.map(function (f) {
      var name = String(f.name || "foto.jpg").replace(/[\\/:*?"<>|]/g, "_");
      var key = name.toLowerCase();
      if (seen[key]) {
        var dot = name.lastIndexOf(".");
        var base = dot > 0 ? name.slice(0, dot) : name;
        var ext = dot > 0 ? name.slice(dot) : "";
        var i = 2;
        while (seen[(base + " (" + i + ")" + ext).toLowerCase()]) i++;
        name = base + " (" + i + ")" + ext;
        key = name.toLowerCase();
      }
      seen[key] = true;
      return name;
    });
  }

  function build(files) {
    var enc = new TextEncoder();
    var names = uniqueNames(files);
    var parts = [];
    var central = [];
    var offset = 0;
    files.forEach(function (f, i) {
      var name = enc.encode(names[i]);
      var data = f.data;
      var date = f.date instanceof Date && !isNaN(f.date) ? f.date : new Date();
      var crc = crc32(data);
      var size = data.length;
      if (offset + size > 0xfffffff0) throw new Error("ZIP maior que 4 GB");
      var local = new DataView(new ArrayBuffer(30));
      local.setUint32(0, 0x04034b50, true);
      local.setUint16(4, 20, true);
      local.setUint16(6, 0x0800, true);
      local.setUint16(8, 0, true);
      local.setUint16(10, dosTime(date), true);
      local.setUint16(12, dosDate(date), true);
      local.setUint32(14, crc, true);
      local.setUint32(18, size, true);
      local.setUint32(22, size, true);
      local.setUint16(26, name.length, true);
      local.setUint16(28, 0, true);
      parts.push(local.buffer, name, data);

      var c = new DataView(new ArrayBuffer(46));
      c.setUint32(0, 0x02014b50, true);
      c.setUint16(4, 20, true);
      c.setUint16(6, 20, true);
      c.setUint16(8, 0x0800, true);
      c.setUint16(10, 0, true);
      c.setUint16(12, dosTime(date), true);
      c.setUint16(14, dosDate(date), true);
      c.setUint32(16, crc, true);
      c.setUint32(20, size, true);
      c.setUint32(24, size, true);
      c.setUint16(28, name.length, true);
      c.setUint32(42, offset, true);
      central.push(c.buffer, name);
      offset += 30 + name.length + size;
    });
    var centralSize = central.reduce(function (n, p) {
      return n + p.byteLength;
    }, 0);
    var end = new DataView(new ArrayBuffer(22));
    end.setUint32(0, 0x06054b50, true);
    end.setUint16(8, files.length, true);
    end.setUint16(10, files.length, true);
    end.setUint32(12, centralSize, true);
    end.setUint32(16, offset, true);
    return new Blob(parts.concat(central, [end.buffer]), { type: "application/zip" });
  }

  window.LumoZip = { build: build, crc32: crc32 };
})();
