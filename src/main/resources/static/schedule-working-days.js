(() => {
    const teacher = document.getElementById('working-days-teacher');
    const fields = document.getElementById('working-days-fields');
    if (!teacher || !fields) return;
    function update() {
        const days = (teacher.selectedOptions[0]?.dataset.days || '').split(',');
        fields.disabled = !teacher.value;
        fields.querySelectorAll('input[name="days"]').forEach(input => {
            input.checked = days.includes(input.value);
        });
    }
    teacher.addEventListener('change', update);
    update();
})();
