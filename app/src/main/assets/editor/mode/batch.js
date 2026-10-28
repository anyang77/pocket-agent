// Windows batch syntax for the bundled CodeMirror 5 editor.
(function (mod) {
  if (typeof exports === "object" && typeof module === "object") mod(require("../../lib/codemirror"));
  else if (typeof define === "function" && define.amd) define(["../../lib/codemirror"], mod);
  else mod(CodeMirror);
})(function (CodeMirror) {
  "use strict";
  const keywords = new Set(("assoc break call cd chdir cls color copy date del dir echo endlocal erase exit " +
    "for ftype goto if md mkdir mklink move path pause popd prompt pushd rd rem ren rename rmdir " +
    "set setlocal shift start time title type ver verify vol else in do not exist defined errorlevel " +
    "equ neq lss leq gtr geq enabledelayedexpansion disabledelayedexpansion enableextensions " +
    "disableextensions off on").split(" "));

  function variable(stream) {
    return stream.match(/^%~[A-Za-z]*(?:\$[A-Za-z_][\w]*:)?[0-9A-Za-z]/) ||
      stream.match(/^%%~[A-Za-z]*[A-Za-z]/) ||
      stream.match(/^%%[A-Za-z]/) ||
      stream.match(/^%[0-9*]/) ||
      stream.match(/^%[^%\r\n]+%/) ||
      stream.match(/^![^!\r\n]+!/);
  }

  CodeMirror.defineMode("batch", function () {
    return {
      startState: function () { return {quoted: false, commandStart: true}; },
      token: function (stream, state) {
        if (stream.sol()) { state.quoted = false; state.commandStart = true; }
        if (variable(stream)) { state.commandStart = false; return "variable-2"; }
        if (stream.match(/^\^./)) return "string-2";
        if (stream.peek() === '"') { stream.next(); state.quoted = !state.quoted; state.commandStart = false; return "string"; }
        if (state.quoted) {
          stream.next();
          stream.eatWhile(/[^"%!^]/);
          return "string";
        }
        if (stream.eatSpace()) return null;
        if (state.commandStart && stream.match(/^::/)) { stream.skipToEnd(); return "comment"; }
        if (state.commandStart && stream.match(/^rem(?:\s|$)/i)) { stream.skipToEnd(); return "comment"; }
        if (state.commandStart && stream.match(/^:[\w.\-]+/)) { state.commandStart = false; return "def"; }
        if (stream.eat("@")) return "meta";
        if (stream.match(/^(?:&&|\|\||[&|])/)) { state.commandStart = true; return "operator"; }
        if (stream.match(/^(?:>>?|<|[=()])/)) { if (stream.current() === "(") state.commandStart = true; return "operator"; }
        if (stream.match(/^\d+\b/)) { state.commandStart = false; return "number"; }
        if (stream.match(/^[A-Za-z_][\w.-]*/)) {
          state.commandStart = false;
          return keywords.has(stream.current().toLowerCase()) ? "keyword" : null;
        }
        stream.next();
        state.commandStart = false;
        return null;
      },
      lineComment: "REM",
      closeBrackets: '()[]{}""',
    };
  });
  CodeMirror.defineMIME("text/x-bat", "batch");
});
