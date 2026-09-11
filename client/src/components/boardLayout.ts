export interface BoardSpace {
  coordinate: string;
  ring: string;
  index: number;
  x: number;
  y: number;
}

export interface BoardLayout {
  spaces: BoardSpace[];
  byCoordinate: Map<string, BoardSpace>;
  buffer: number;
  cellRadius: number;
  spacing: number;
  size: number;
}

const TAU = Math.PI * 2;

function axis(symmetry: number, index: number) {
  const angle = TAU / 12 + (index / symmetry) * TAU;
  return { x: Math.cos(angle), y: Math.sin(angle) };
}

export function buildBoardLayout(
  rings: string[],
  symmetry = 6,
  cellRadius = 40,
  buffer = 2.1,
): BoardLayout {
  const spacing = cellRadius * buffer;
  const field = Math.max(2, rings.length) * spacing;
  const center = field;
  const ringPoints = rings.map(() => [] as Array<{ x: number; y: number }>);
  ringPoints[0]?.push({ x: center, y: center });

  for (let segment = 0; segment < symmetry; segment += 1) {
    const start = axis(symmetry, segment);
    const end = axis(symmetry, (segment + 1) % symmetry);
    for (let level = 1; level < rings.length; level += 1) {
      ringPoints[level].push({
        x: center + start.x * level * spacing,
        y: center + start.y * level * spacing,
      });
      for (let step = level - 1; step >= 1; step -= 1) {
        const ratio = step / level;
        ringPoints[level].push({
          x: center + (start.x * ratio + end.x * (1 - ratio)) * level * spacing,
          y: center + (start.y * ratio + end.y * (1 - ratio)) * level * spacing,
        });
      }
    }
  }

  const spaces = ringPoints.flatMap((points, ringIndex) =>
    points.map((point, index) => ({
      coordinate: `${rings[ringIndex]}:${index}`,
      ring: rings[ringIndex],
      index,
      ...point,
    })),
  );

  return {
    spaces,
    byCoordinate: new Map(spaces.map((space) => [space.coordinate, space])),
    buffer,
    cellRadius,
    spacing,
    size: field * 2,
  };
}
