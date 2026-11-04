import { useEffect, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Eye, Info, PencilLine } from 'lucide-react';
import { useSaveArticle } from '../../api/knowledge';
import { useServices } from '../../api/services';
import { idOrNull, SelectField, TextareaField, TextField } from '../../components/FormField';
import { ARTICLE_TYPE_LABELS } from '../../components/knowledge/ArticleRow';
import { Markdown } from '../../components/Markdown';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/Toast';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { ARTICLE_TYPES, CATEGORIES } from '../../lib/constants';
import { splitList, titleCase } from '../../lib/format';
import { articleSchema } from '../../lib/schemas';
import type { ArticleDetail, ArticleRequest, ArticleType } from '../../lib/types';

/** The editor has no status picker (§9): saving keeps a new article a draft; publishing is a separate action. */
export const articleEditorSchema = articleSchema.omit({ status: true }).extend({
  changeNote: z.string().trim().max(500, 'Keep the change note under 500 characters'),
});
export type ArticleEditorValues = z.infer<typeof articleEditorSchema>;

/** Request body for a save: new articles are created as drafts; an edit never changes the status. */
export function toArticleRequest(values: ArticleEditorValues, isNew: boolean): ArticleRequest {
  const body: ArticleRequest = {
    title: values.title.trim(),
    summary: values.summary.trim(),
    content: values.content,
    articleType: values.articleType,
    category: values.category,
    serviceId: idOrNull(values.serviceId),
    tags: splitList(values.tags),
  };
  if (isNew) body.status = 'DRAFT';
  const note = values.changeNote.trim();
  if (note) body.changeNote = note;
  return body;
}

interface ArticleEditorProps {
  article: ArticleDetail | null;
  defaultType?: ArticleType;
  onClose: () => void;
  onSaved?: (saved: ArticleDetail) => void;
}

export function ArticleEditor({ article, defaultType = 'GUIDE', onClose, onSaved }: ArticleEditorProps) {
  const save = useSaveArticle();
  const services = useServices();
  const toast = useToast();
  const [preview, setPreview] = useState(false);
  const isNew = article === null;
  const {
    register,
    handleSubmit,
    control,
    setError,
    setValue,
    getValues,
    formState: { errors },
  } = useForm<ArticleEditorValues>({
    resolver: zodResolver(articleEditorSchema),
    defaultValues: {
      title: article?.title ?? '',
      summary: article?.summary ?? '',
      content: article?.content ?? '',
      articleType: article?.articleType ?? defaultType,
      category: article?.category ?? 'APPLICATION',
      serviceId: article?.serviceId ? String(article.serviceId) : '',
      tags: article?.tags.join(', ') ?? '',
      changeNote: '',
    },
  });
  const content = useWatch({ control, name: 'content' });
  // The service options can arrive after the form mounted: re-apply the selected service once they are there.
  useEffect(() => {
    if (services.data) setValue('serviceId', getValues('serviceId'));
  }, [services.data, getValues, setValue]);

  const onSubmit = handleSubmit((values) =>
    save.mutate(
      { id: article?.id, body: toArticleRequest(values, isNew) },
      {
        onSuccess: (saved) => {
          toast.success(isNew ? `${saved.number} saved as a draft` : `${saved.number} updated`);
          onSaved?.(saved);
          onClose();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  const statusNote = isNew
    ? 'New articles are saved as drafts. Submit it for review — or publish it, if you are a knowledge publisher — from the article page.'
    : article.status === 'PUBLISHED'
      ? 'Saving stores a new version of this published article. Previous versions can be restored from the article page.'
      : `Saving keeps the article ${titleCase(article.status).toLowerCase()}; publishing is a separate action on the article page.`;

  return (
    <Modal open onClose={onClose} title={isNew ? 'Create knowledge article' : `Edit ${article.number}`} eyebrow="KNOWLEDGE BASE" size="xl" dismissible={!save.isPending}>
      <form onSubmit={onSubmit} noValidate>
        <div className="callout mb-3">
          <Info size={16} />
          <div>{statusNote}</div>
        </div>
        <TextField label="Title" required error={errors.title?.message} {...register('title')} />
        <TextareaField label="Summary" required error={errors.summary?.message} rows={2} {...register('summary')} />
        <div className="form-row three">
          <SelectField label="Type" error={errors.articleType?.message} {...register('articleType')}>
            {ARTICLE_TYPES.map((type) => (
              <option key={type} value={type}>
                {ARTICLE_TYPE_LABELS[type]}
              </option>
            ))}
          </SelectField>
          <SelectField label="Category" error={errors.category?.message} {...register('category')}>
            {CATEGORIES.map((category) => (
              <option key={category} value={category}>
                {titleCase(category)}
              </option>
            ))}
          </SelectField>
          <SelectField label="Service" error={errors.serviceId?.message} {...register('serviceId')}>
            <option value="">No specific service</option>
            {services.data?.map((service) => (
              <option key={service.id} value={service.id}>
                {service.name}
              </option>
            ))}
          </SelectField>
        </div>
        <TextField label="Tags" hint="Comma separated, e.g. payroll, vpn, outlook" error={errors.tags?.message} {...register('tags')} />
        <div className="mt-3 flex items-center justify-between gap-2">
          <span className="text-[10px] text-muted">Content (Markdown: # headings, lists, **bold**, *italic*, `code`, ``` blocks, [links](https://…))</span>
          <button type="button" className="subtle-btn compact" onClick={() => setPreview((value) => !value)} aria-pressed={preview}>
            {preview ? <PencilLine size={13} /> : <Eye size={13} />} {preview ? 'Edit' : 'Preview'}
          </button>
        </div>
        {preview ? (
          <div className="panel mt-2 max-h-[420px] overflow-auto">
            <Markdown source={content || '_Nothing to preview yet._'} />
          </div>
        ) : (
          <TextareaField label="Content" required rows={14} error={errors.content?.message} className="!mt-2" {...register('content')} />
        )}
        <TextField
          label="Change note"
          hint={isNew ? 'Optional — recorded with the first version.' : 'Optional — shown in the version history.'}
          placeholder={isNew ? 'Initial version' : 'What changed?'}
          maxLength={500}
          error={errors.changeNote?.message}
          {...register('changeNote')}
        />
        {save.isError && !Object.keys(errors).length && <p className="field-error">{errorMessage(save.error)}</p>}
        <div className="modal-foot">
          <button type="button" className="subtle-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="gold-btn" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : isNew || article.status === 'DRAFT' ? 'Save draft' : 'Save changes'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
