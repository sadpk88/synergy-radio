const state = {
  recentTracks: Array.from({ length: 1000 }, (_, i) => ({ artist: `Artist ${i}`, title: `Title ${i}` })),
  favorites: Array.from({ length: 500 }, (_, i) => ({ key: `Artist ${i * 2} - Title ${i * 2}` }))
};

function original() {
  let count = 0;
  state.recentTracks.map(item => {
    const key = `${item.artist} - ${item.title}`;
    const isFav = state.favorites.some(f => f.key === key);
    if (isFav) count++;
  });
  return count;
}

function optimized() {
  let count = 0;
  const favoriteKeys = new Set(state.favorites.map(f => f.key));
  state.recentTracks.map(item => {
    const key = `${item.artist} - ${item.title}`;
    const isFav = favoriteKeys.has(key);
    if (isFav) count++;
  });
  return count;
}

const iterations = 1000;

console.time('original');
for (let i = 0; i < iterations; i++) {
  original();
}
console.timeEnd('original');

console.time('optimized');
for (let i = 0; i < iterations; i++) {
  optimized();
}
console.timeEnd('optimized');
