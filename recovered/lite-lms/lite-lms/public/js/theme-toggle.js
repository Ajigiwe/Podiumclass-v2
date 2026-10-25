// public/js/theme-toggle.js
const initThemeToggle = () => {
    const themeToggles = document.querySelectorAll('#theme-toggle');
    const themeIcons = document.querySelectorAll('#theme-icon');

    if (themeToggles.length === 0) return;

    // Sync icons with current theme
    const isDark = document.documentElement.classList.contains('dark');
    themeIcons.forEach(icon => {
        if (isDark) {
            icon.classList.remove('fa-moon');
            icon.classList.add('fa-sun');
        } else {
            icon.classList.remove('fa-sun');
            icon.classList.add('fa-moon');
        }
    });

    themeToggles.forEach(toggle => {
        toggle.addEventListener('click', () => {
            const currentlyDark = document.documentElement.classList.toggle('dark');
            localStorage.setItem('podium-theme', currentlyDark ? 'dark' : 'light');

            // Update ALL icons on the page
            document.querySelectorAll('#theme-icon').forEach(icon => {
                if (currentlyDark) {
                    icon.classList.remove('fa-moon');
                    icon.classList.add('fa-sun');
                } else {
                    icon.classList.remove('fa-sun');
                    icon.classList.add('fa-moon');
                }
            });
        });
    });
};

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initThemeToggle);
} else {
    initThemeToggle();
}
