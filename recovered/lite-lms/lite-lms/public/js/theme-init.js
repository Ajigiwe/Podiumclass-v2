// public/js/theme-init.js
(function() {
    const theme = localStorage.getItem('podium-theme') || 'light';
    if (theme === 'dark') {
        document.documentElement.classList.add('dark');
    } else {
        document.documentElement.classList.remove('dark');
    }
})();
