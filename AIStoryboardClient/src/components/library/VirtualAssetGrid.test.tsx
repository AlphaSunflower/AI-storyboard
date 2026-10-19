import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { createRef } from 'react';
import { VirtualAssetGrid } from './VirtualAssetGrid';

describe('VirtualAssetGrid', () => {
  it('mounts only visible rows for a large list', () => {
    const scrollRef = createRef<HTMLDivElement>();
    scrollRef.current = document.createElement('div');
    const items = Array.from({ length: 200 }, (_, index) => ({ id: `asset-${index}` }));

    render(
      <VirtualAssetGrid
        items={items}
        scrollRef={scrollRef}
        renderItem={(item) => <span data-testid="virtual-asset">{item.id}</span>}
      />,
    );

    const rendered = screen.getAllByTestId('virtual-asset');
    expect(rendered.length).toBeGreaterThan(0);
    expect(rendered.length).toBeLessThan(items.length);
  });
});
