import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AlertModal from '@/components/AlertModal';

describe('AlertModal', () => {
  it('renders nothing when isOpen is false', () => {
    const { container } = render(
      <AlertModal isOpen={false} onClose={() => {}} title="Test" message="Hello" />
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders title and message when open', () => {
    render(
      <AlertModal isOpen={true} onClose={() => {}} title="Test Title" message="Test Message" />
    );
    expect(screen.getByText('Test Title')).toBeInTheDocument();
    expect(screen.getByText('Test Message')).toBeInTheDocument();
  });

  it('renders confirm button with default text "OK"', () => {
    render(
      <AlertModal isOpen={true} onClose={() => {}} title="T" message="M" />
    );
    expect(screen.getByRole('button', { name: 'OK' })).toBeInTheDocument();
  });

  it('renders cancel button when showCancel is true', () => {
    render(
      <AlertModal isOpen={true} onClose={() => {}} title="T" message="M" showCancel={true} />
    );
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
  });

  it('hides cancel button by default', () => {
    render(
      <AlertModal isOpen={true} onClose={() => {}} title="T" message="M" />
    );
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
  });

  it('calls onConfirm when confirm button is clicked', async () => {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    render(
      <AlertModal isOpen={true} onClose={onClose} title="T" message="M" onConfirm={onConfirm} />
    );
    await userEvent.click(screen.getByRole('button', { name: 'OK' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('calls onCancel when cancel button is clicked', async () => {
    const onCancel = vi.fn();
    const onClose = vi.fn();
    render(
      <AlertModal
        isOpen={true}
        onClose={onClose}
        title="T"
        message="M"
        showCancel={true}
        onCancel={onCancel}
      />
    );
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('uses custom button text', () => {
    render(
      <AlertModal
        isOpen={true}
        onClose={() => {}}
        title="T"
        message="M"
        confirmText="Yes"
        cancelText="No"
        showCancel={true}
      />
    );
    expect(screen.getByRole('button', { name: 'Yes' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'No' })).toBeInTheDocument();
  });

  it('renders info icon by default', () => {
    const { container } = render(
      <AlertModal isOpen={true} onClose={() => {}} title="T" message="M" />
    );
    const icon = container.querySelector('.text-blue-500');
    expect(icon).toBeTruthy();
  });

  it('renders success icon when type is success', () => {
    const { container } = render(
      <AlertModal isOpen={true} onClose={() => {}} title="T" message="M" type="success" />
    );
    const icon = container.querySelector('.text-green-500');
    expect(icon).toBeTruthy();
  });

  it('renders error icon when type is error', () => {
    const { container } = render(
      <AlertModal isOpen={true} onClose={() => {}} title="T" message="M" type="error" />
    );
    const icon = container.querySelector('.text-red-500');
    expect(icon).toBeTruthy();
  });

  it('renders warning icon when type is warning', () => {
    const { container } = render(
      <AlertModal isOpen={true} onClose={() => {}} title="T" message="M" type="warning" />
    );
    const icon = container.querySelector('.text-amber-500');
    expect(icon).toBeTruthy();
  });
});
