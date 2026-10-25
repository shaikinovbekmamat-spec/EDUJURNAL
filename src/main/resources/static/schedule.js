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

const subjectSelect = document.getElementById('subjectId');
const teacherSelect = document.getElementById('teacherId');
function filterTeachers() {
    const selected = subjectSelect.selectedOptions[0];
    const allowedTeachers = (selected?.dataset.teachers || '').split(',');
    for (const option of teacherSelect.options) {
        const allowed = !option.value || allowedTeachers.includes(option.value);
        option.hidden = !allowed;
        option.disabled = !allowed;
        if (!allowed && option.selected) teacherSelect.value = '';
    }
}
subjectSelect.addEventListener('change', filterTeachers);
filterTeachers();

const repeat = document.getElementById('repeatWeekly');
const until = document.getElementById('repeatUntil');
const firstDate = document.getElementById('date');
if (repeat && until) {
    function updateRepeat() {
        const enabled = repeat.type === 'hidden' ? repeat.value === 'true' : repeat.checked;
        until.disabled = !enabled;
        until.required = enabled;
        until.min = firstDate.value;
    }
    repeat.addEventListener('change', updateRepeat);
    firstDate.addEventListener('change', updateRepeat);
    updateRepeat();
}
