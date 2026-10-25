/**
 * Sidebar Mobile Toggle Logic
 */
document.addEventListener('DOMContentLoaded', () => {
    const mobileMenuBtn = document.getElementById('mobile-menu-btn');
    const closeMobileMenuBtn = document.getElementById('close-mobile-menu');
    const sidebar = document.getElementById('sidebar');
    const overlay = document.getElementById('sidebar-overlay');

    if (!mobileMenuBtn || !sidebar || !overlay) return;

    const toggleSidebar = (show) => {
        if (show) {
            sidebar.classList.remove('hidden', '-translate-x-full');
            overlay.classList.remove('hidden');
            document.body.classList.add('overflow-hidden');
        } else {
            sidebar.classList.add('-translate-x-full');
            overlay.classList.add('hidden');
            document.body.classList.remove('overflow-hidden');
            // Wait for transition before hiding
            setTimeout(() => {
                if (sidebar.classList.contains('-translate-x-full')) {
                    sidebar.classList.add('hidden');
                }
            }, 300);
        }
    };

    mobileMenuBtn.addEventListener('click', () => toggleSidebar(true));
    if (closeMobileMenuBtn) closeMobileMenuBtn.addEventListener('click', () => toggleSidebar(false));
    overlay.addEventListener('click', () => toggleSidebar(false));

    // Handle resize
    window.addEventListener('resize', () => {
        if (window.innerWidth >= 1024) { // lg breakpoint
            sidebar.classList.remove('hidden', '-translate-x-full');
            overlay.classList.add('hidden');
            document.body.classList.remove('overflow-hidden');
        } else {
            sidebar.classList.add('hidden', '-translate-x-full');
        }
    });
});
