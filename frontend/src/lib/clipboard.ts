import { toast } from '../stores/toast';

/**
 * Copy text, with the fallback a non-secure origin needs.
 *
 * `navigator.clipboard` only exists on HTTPS (and localhost), so a host running
 * the class over a LAN IP would otherwise get an exception instead of a copy.
 */
export async function copyText(text: string): Promise<boolean> {
  if (!text) return false;
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fall through to the older path */
  }
  try {
    const el = document.createElement('textarea');
    el.value = text;
    el.setAttribute('readonly', '');
    el.style.position = 'fixed';
    el.style.top = '-1000px';
    el.style.opacity = '0';
    document.body.appendChild(el);
    el.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(el);
    return ok;
  } catch {
    return false;
  }
}

/**
 * Copy and tell the user how it went.
 *
 * Every copy in the app goes through here so the feedback reads the same on the
 * dashboard, in a classroom and on the admin panel.
 */
export async function copyWithToast(text: string, label: string): Promise<boolean> {
  const ok = await copyText(text);
  if (ok) {
    toast.success(`${label} copied`, text);
  } else {
    toast.error('Could not copy', 'Select the text and copy it by hand instead.');
  }
  return ok;
}
