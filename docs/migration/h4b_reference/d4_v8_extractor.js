/**
 * d4_v8_extractor.js — M6.10 D4 V8 120-feature extractor (SHADOW candidate, pure CommonJS)
 *
 * Level 1 image ops (gray / Sobel gx,gy / magnitude / Laplacian ksize=3)
 * are supplied by host OpenCV. Level 2 feature math below is frozen.
 */
'use strict';
var EPS = 1e-4;
var REGIONS = {
  all:      { x1: 5,  y1: 5,  x2: 105, y2: 79 },
  mid:      { x1: 20, y1: 15, x2: 90,  y2: 60 },
  lower:    { x1: 15, y1: 30, x2: 95,  y2: 79 },
  center:   { x1: 30, y1: 25, x2: 80,  y2: 75 },
  feet:     { x1: 30, y1: 45, x2: 80,  y2: 79 },
  corefeet: { x1: 38, y1: 50, x2: 72,  y2: 79 },
};
function _ratio(a, b) { return (a + EPS) / (b + EPS); }
function percentileSorted(sorted, q) {
  var n = sorted.length;
  if (n === 0) return NaN;
  if (n === 1) return sorted[0];
  var rank = (n - 1) * (q / 100);
  var i = Math.floor(rank);
  var frac = rank - i;
  if (i + 1 < n) return sorted[i] + (sorted[i + 1] - sorted[i]) * frac;
  return sorted[i];
}
function percentile(arr, q) {
  var s = arr.slice().sort((a, b) => a - b);
  return percentileSorted(s, q);
}
function std(arr) {
  var n = arr.length;
  if (n === 0) return NaN;
  let m = 0;
  for (let i = 0; i < n; i++) m += arr[i];
  m /= n;
  let acc = 0;
  for (let i = 0; i < n; i++) { var d = arr[i] - m; acc += d * d; }
  return Math.sqrt(acc / n);
}
function meanStrictGreater(arr, t) {
  let c = 0;
  for (let i = 0; i < arr.length; i++) if (arr[i] > t) c++;
  return c / arr.length;
}
function absArr(a) { var o = new Array(a.length); for (let i = 0; i < a.length; i++) o[i] = Math.abs(a[i]); return o; }
function computeFeaturesFromMaps(maps) {
  var W = 110, H = 80;
  var slice = (arr, y1, y2, x1, x2) => {
    var out = [];
    for (let y = y1; y < y2; y++) {
      var base = y * W;
      for (let x = x1; x < x2; x++) out.push(arr[base + x]);
    }
    return out;
  };
  var d = {};
  for (var _i0 = 0, _a0 = Object.keys(REGIONS); _i0 < _a0.length; _i0++) { var name = _a0[_i0];
    var r = REGIONS[name];
    var gr = slice(maps.gray, r.y1, r.y2, r.x1, r.x2);
    var mr = slice(maps.mag, r.y1, r.y2, r.x1, r.x2);
    var gxr = slice(maps.gx, r.y1, r.y2, r.x1, r.x2);
    var gyr = slice(maps.gy, r.y1, r.y2, r.x1, r.x2);
    var lapr = slice(maps.lap, r.y1, r.y2, r.x1, r.x2);
    d[name + '_std'] = std(gr);
    for (var _i1 = 0, _a1 = [50, 75, 85, 90, 95]; _i1 < _a1.length; _i1++) { var q = _a1[_i1]; d[name + '_g' + q] = percentile(mr, q); }
    for (var _i2 = 0, _a2 = [15, 25, 40, 60]; _i2 < _a2.length; _i2++) { var t = _a2[_i2]; d[name + '_e' + t] = meanStrictGreater(mr, t); }
    d[name + '_gx75'] = percentile(absArr(gxr), 75);
    d[name + '_gy75'] = percentile(absArr(gyr), 75);
    d[name + '_lap75'] = percentile(absArr(lapr), 75);
  }
  for (var _i3 = 0, _a3 = [15, 25, 40, 60]; _i3 < _a3.length; _i3++) { var t = _a3[_i3];
    d['feet_to_lower_e' + t] = _ratio(d['feet_e' + t], d['lower_e' + t]);
    d['corefeet_to_lower_e' + t] = _ratio(d['corefeet_e' + t], d['lower_e' + t]);
  }
  for (var _i4 = 0, _a4 = [75, 90, 95]; _i4 < _a4.length; _i4++) { var q = _a4[_i4];
    d['feet_to_lower_g' + q] = _ratio(d['feet_g' + q], d['lower_g' + q]);
  }
  for (var _i5 = 0, _a5 = [15, 25, 40, 60]; _i5 < _a5.length; _i5++) { var t = _a5[_i5];
    d['center_to_all_e' + t] = _ratio(d['center_e' + t], d['all_e' + t]);
    d['feet_to_all_e' + t] = _ratio(d['feet_e' + t], d['all_e' + t]);
    d['corefeet_to_all_e' + t] = _ratio(d['corefeet_e' + t], d['all_e' + t]);
    d['corefeet_to_center_e' + t] = _ratio(d['corefeet_e' + t], d['center_e' + t]);
  }
  for (var _i6 = 0, _a6 = [75, 90, 95]; _i6 < _a6.length; _i6++) { var q = _a6[_i6];
    d['center_to_all_g' + q] = _ratio(d['center_g' + q], d['all_g' + q]);
    d['feet_to_all_g' + q] = _ratio(d['feet_g' + q], d['all_g' + q]);
    d['corefeet_to_all_g' + q] = _ratio(d['corefeet_g' + q], d['all_g' + q]);
    d['corefeet_to_center_g' + q] = _ratio(d['corefeet_g' + q], d['center_g' + q]);
  }
  for (var _i7 = 0, _a7 = ['center', 'feet', 'corefeet']; _i7 < _a7.length; _i7++) { var s = _a7[_i7];
    d[s + '_to_all_std'] = _ratio(d[s + '_std'], d['all_std']);
  }
  return d;
}
function featureVectorFromMaps(maps, featureOrder) {
  var d = computeFeaturesFromMaps(maps);
  var v = new Array(featureOrder.length);
  for (let i = 0; i < featureOrder.length; i++) v[i] = d[featureOrder[i]];
  return v;
}
module.exports = { EPS, REGIONS, percentile, percentileSorted, std, meanStrictGreater, absArr, _ratio, computeFeaturesFromMaps, featureVectorFromMaps };
