export const reducedMotion = (): boolean => matchMedia('(prefers-reduced-motion: reduce)').matches;

export const sleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, reducedMotion() ? Math.min(ms, 60) : ms));

/** Calls back whenever the element enters or leaves the viewport. */
export function watchVisibility(el: Element, onChange: (visible: boolean) => void, threshold = 0.25): void {
  if (!('IntersectionObserver' in window)) {
    onChange(true);
    return;
  }
  new IntersectionObserver((entries) => entries.forEach((e) => onChange(e.isIntersecting)), {threshold}).observe(el);
}

type Box = {left: number; right: number; top: number; bottom: number; cx: number; cy: number};

const relative = (r: DOMRect, origin: DOMRect): Box => {
  const left = r.left - origin.left;
  const top = r.top - origin.top;
  return {left, top, right: left + r.width, bottom: top + r.height, cx: left + r.width / 2, cy: top + r.height / 2};
};

/** A smooth cubic path from element `a` to element `b`, in `origin`'s coordinates. */
export function connectorPath(a: Element, b: Element, origin: Element): string {
  const o = origin.getBoundingClientRect();
  const A = relative(a.getBoundingClientRect(), o);
  const B = relative(b.getBoundingClientRect(), o);
  if (B.left >= A.right - 2) {
    const dx = Math.max((B.left - A.right) / 2, 12);
    return `M${A.right} ${A.cy} C${A.right + dx} ${A.cy} ${B.left - dx} ${B.cy} ${B.left} ${B.cy}`;
  }
  if (B.top >= A.bottom - 2) {
    const dy = Math.max((B.top - A.bottom) / 2, 12);
    return `M${A.cx} ${A.bottom} C${A.cx} ${A.bottom + dy} ${B.cx} ${B.top - dy} ${B.cx} ${B.top}`;
  }
  return `M${A.cx} ${A.cy} L${B.cx} ${B.cy}`;
}

const ease = (t: number): number => (t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2);

/** Moves `packet` along `path` (optionally backwards) and resolves on arrival. */
export function travel(packet: HTMLElement, path: SVGPathElement, duration: number, reverse = false): Promise<void> {
  const length = path.getTotalLength();
  const place = (t: number) => {
    const p = path.getPointAtLength(length * (reverse ? 1 - t : t));
    packet.style.transform = `translate(${p.x}px, ${p.y}px)`;
  };
  if (reducedMotion() || duration <= 0) {
    place(1);
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const start = performance.now();
    const frame = (now: number) => {
      const t = Math.min((now - start) / duration, 1);
      place(ease(t));
      if (t < 1) requestAnimationFrame(frame);
      else resolve();
    };
    requestAnimationFrame(frame);
  });
}

/** Animates a number in `el` from its current value to `to`. */
export function countTo(el: HTMLElement, to: number, decimals: number, duration = 900, prefix = ''): void {
  const from = Number(el.dataset.current ?? '0');
  el.dataset.current = String(to);
  const render = (v: number) => {
    el.textContent = prefix + v.toFixed(decimals);
  };
  if (reducedMotion()) {
    render(to);
    return;
  }
  const start = performance.now();
  const frame = (now: number) => {
    const t = Math.min((now - start) / duration, 1);
    render(from + (to - from) * (1 - (1 - t) ** 3));
    if (t < 1) requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}
