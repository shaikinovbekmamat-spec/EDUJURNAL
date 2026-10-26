const role = document.getElementById('account-role');
function updateProfileFields() {
    document.querySelectorAll('[data-role]').forEach(label => {
        const active = label.dataset.role === role.value;
        label.hidden = !active;
        const select = label.querySelector('select');
        select.disabled = !active;
        select.required = active && label.dataset.role === 'STUDENT';
    });
}
if (role) { role.addEventListener('change', updateProfileFields); updateProfileFields(); }
