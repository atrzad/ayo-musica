// The song identity shared by the phone, the desktop and the server ("which song is this?" across devices that each
// have their own files): artist and title folded (no accents, lower case, single spaces) and whole seconds.
//   artist|title|seconds        e.g. "seu pereira e coletivo 401|obsoleto|223"
// Two keys are the same song when artist and title match and the seconds differ by 3 at most.
export function fold(text) {
  return String(text || '').normalize('NFKD').replace(/\p{M}+/gu, '').toLowerCase().replace(/\s+/g, ' ').trim();
}

export function songKey(artist, title, durationMs) {
  return `${fold(artist)}|${fold(title)}|${Math.floor((Number(durationMs) || 0) / 1000)}`;
}
