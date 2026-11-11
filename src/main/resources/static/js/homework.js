document.addEventListener('DOMContentLoaded', () => {
    const classSelect = document.querySelector('[data-homework-class]');
    const subjectSelect = document.querySelector('[data-homework-subject]');
    if (classSelect && subjectSelect) {
        const updateSubjects = () => {
            for (const option of subjectSelect.options) {
                if (!option.value) continue;
                const available = option.dataset.class === classSelect.value;
                option.disabled = !available;
                option.hidden = !available;
                if (!available && option.selected) subjectSelect.value = '';
            }
        };
        classSelect.addEventListener('change', updateSubjects);
        updateSubjects();
    }
    for (const input of document.querySelectorAll('input[type=file][data-homework-files]')) {
        input.addEventListener('change', () => {
            input.setCustomValidity('');
            if (input.files.length > 5) input.setCustomValidity('Выберите не более пяти файлов.');
            else if ([...input.files].some(file => file.size > 10 * 1024 * 1024)) input.setCustomValidity('Каждый файл должен быть не больше 10 МБ.');
            input.reportValidity();
        });
    }
    for (const form of document.querySelectorAll('[data-homework-check]')) {
        const comment = form.querySelector('textarea[name=comment]');
        comment.addEventListener('input', () => comment.setCustomValidity(''));
        for (const button of form.querySelectorAll('button[type=submit]')) {
            button.addEventListener('click', () => comment.setCustomValidity(''));
        }
        form.addEventListener('submit', event => {
            comment.setCustomValidity('');
            if (event.submitter?.value === 'RETURNED' && !comment.value.trim()) {
                event.preventDefault();
                comment.setCustomValidity('Укажите, что нужно доработать.');
                comment.reportValidity();
            }
        });
    }
});
