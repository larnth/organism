export function FoodDots({
  count,
  radius,
}: {
  count: number;
  radius: number;
}) {
  if (count <= 0) return null;
  if (count > 6) {
    return (
      <g className="food-dots food-dots--count">
        <circle data-food-dot r={radius * 0.24} />
        <text y={radius * 0.025}>{count}</text>
      </g>
    );
  }

  const orbit = count === 1 ? 0 : radius * 0.26;
  const scale = radius / 36;
  return (
    <g className="food-dots">
      {Array.from({ length: count }, (_, index) => {
        const angle = -Math.PI / 2 + (index / count) * Math.PI * 2;
        return (
          <g
            key={index}
            transform={`translate(${Math.cos(angle) * orbit} ${Math.sin(angle) * orbit}) rotate(${(angle * 180) / Math.PI + 90}) scale(${scale})`}
          >
            <path
              className="food-seed"
              data-food-dot
              d="M0-5C4.8-5 7-1.2 4.2 2.4 2.2 5.2-2.5 6-4.8 2.1-7-1.7-4.3-5 0-5Z"
            />
            <path
              className="food-seed__shine"
              d="M-1.8-2.8C.2-3.8 2.2-2.8 2.8-1.4"
            />
          </g>
        );
      })}
    </g>
  );
}
