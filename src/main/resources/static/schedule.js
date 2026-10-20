const classSelect = document.getElementById('classId');
const subgroupSelect = document.getElementById('subgroupId');

function filterSubgroups(resetInvalid) {
    for (const option of subgroupSelect.options) {
        const allowed = !option.value || option.dataset.class === classSelect.value;
        option.hidden = !allowed;
        option.disabled = !allowed;
        if (!allowed && option.selected && resetInvalid) subgroupSelect.value = '';
    }
}
classSelect.addEventListener('change', () => filterSubgroups(true));
filterSubgroups(true);

const repeat = document.getElementById('repeatWeekly');
const until = document.getElementById('repeatUntil');
const firstDate = document.getElementById('date');
if (repeat && until) {
    function updateRepeat() {
        until.disabled = !repeat.checked;
        until.required = repeat.checked;
        until.min = firstDate.value;
    }
    repeat.addEventListener('change', updateRepeat);
    firstDate.addEventListener('change', updateRepeat);
    updateRepeat();
}
