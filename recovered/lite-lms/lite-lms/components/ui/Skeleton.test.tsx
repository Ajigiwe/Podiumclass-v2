import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Skeleton } from '@/components/ui/Skeleton';

describe('Skeleton', () => {
  it('renders a div with animate-pulse class', () => {
    render(<Skeleton data-testid="skeleton" />);
    const el = screen.getByTestId('skeleton');
    expect(el).toBeInTheDocument();
    expect(el.className).toContain('animate-pulse');
  });

  it('merges additional className', () => {
    render(<Skeleton className="h-10 w-20" data-testid="skeleton" />);
    const el = screen.getByTestId('skeleton');
    expect(el.className).toContain('h-10');
    expect(el.className).toContain('w-20');
  });

  it('forwards additional HTML attributes', () => {
    render(<Skeleton data-testid="skeleton" aria-label="loading" />);
    const el = screen.getByTestId('skeleton');
    expect(el).toHaveAttribute('aria-label', 'loading');
  });
});
