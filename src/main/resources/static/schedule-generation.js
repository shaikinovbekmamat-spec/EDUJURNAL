(() => {
    const form = document.getElementById('curriculum-form');
    if (form) {
        const update = () => {
            let hours = 0;
            form.querySelectorAll('.weekly-hours').forEach(input => {
                hours += Number(input.value) || 0;
                input.closest('tr').classList.toggle('unused-subject', Number(input.value) === 0);
            });
            document.getElementById('weekly-total').textContent = hours;
        };
        form.addEventListener('input', update);
        update();
    }
    const filter = document.getElementById('draft-class-filter');
    if (filter) filter.addEventListener('change', () => {
        document.querySelectorAll('.draft-class').forEach(section => {
            section.hidden = filter.value !== '' && section.dataset.class !== filter.value;
        });
    });
})();
