/** Pick a visible control in the requested direction, preferring the same
 * row/column. DOM order is only a tie-breaker; reaching an edge never wraps. */
export function nextFocus(rects, active, direction) {
  const origin = rects[active];
  if (!origin || !["LEFT", "RIGHT", "UP", "DOWN"].includes(direction)) return active;
  const horizontal = direction === "LEFT" || direction === "RIGHT";
  const sign = direction === "LEFT" || direction === "UP" ? -1 : 1;
  const axes = (rect) => horizontal
    ? [(rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2, rect.top, rect.bottom]
    : [(rect.top + rect.bottom) / 2, (rect.left + rect.right) / 2, rect.left, rect.right];
  const [main, cross, start, end] = axes(origin);
  let best = active;
  let bestAligned = false;
  let bestDistance = Infinity;
  rects.forEach((rect, index) => {
    if (index === active || rect.right <= rect.left || rect.bottom <= rect.top) return;
    const [nextMain, nextCross, nextStart, nextEnd] = axes(rect);
    const forward = (nextMain - main) * sign;
    if (forward <= 1) return;
    const aligned = Math.min(end, nextEnd) > Math.max(start, nextStart);
    const distance = forward * forward + (nextCross - cross) ** 2 * 4;
    if ((aligned && !bestAligned) || (aligned === bestAligned && distance < bestDistance)) {
      best = index;
      bestAligned = aligned;
      bestDistance = distance;
    }
  });
  return best;
}
