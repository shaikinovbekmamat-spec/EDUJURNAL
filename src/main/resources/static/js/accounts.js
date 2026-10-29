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

const managementLinks = document.querySelectorAll('.management-header .tabs a');
function updateManagementNavigation() {
    const selected = window.location.hash || managementLinks[0]?.hash;
    managementLinks.forEach(link => {
        const active = link.hash === selected;
        link.classList.toggle('active', active);
        if (active) link.setAttribute('aria-current', 'location');
        else link.removeAttribute('aria-current');
    });
}
window.addEventListener('hashchange', updateManagementNavigation);
updateManagementNavigation();

document.querySelectorAll('form[data-confirm]').forEach(form => {
    form.addEventListener('submit', event => {
        if (!window.confirm(form.dataset.confirm)) event.preventDefault();
    });
});
