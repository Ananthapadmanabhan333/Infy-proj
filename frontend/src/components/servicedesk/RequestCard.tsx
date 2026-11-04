import { MessageSquare } from 'lucide-react';
import { DemoBadge, PriorityBadge, StatusBadge } from '../Badge';
import { formatDateTime, formatRelative, shortName } from '../../lib/format';
import type { ServiceRequestDto } from '../../lib/types';
import { categoryIcon } from './requestModel';

interface RequestCardProps {
  request: ServiceRequestDto;
  onOpen: (request: ServiceRequestDto) => void;
  /** Adds the requester to the meta line (staff queue). */
  showRequester?: boolean;
  /** Icon name of the request's catalogue category (configured categories). */
  iconName?: string | null;
}

/**
 * Request row from the reference Service Desk: category icon tile, title, "REQ-3921 • Access Management",
 * priority badge, status badge and relative time. The whole card opens the request drawer.
 */
export function RequestCard({ request, onOpen, showRequester, iconName }: RequestCardProps) {
  const Icon = categoryIcon(request.categoryCode, iconName);
  return (
    <li>
      <button
        type="button"
        className="group flex w-full flex-wrap items-center gap-x-3 gap-y-2 rounded-[10px] border border-line bg-panel-raised/60 px-3 py-2.5 text-left transition-colors hover:border-bronze hover:bg-panel-raised focus-visible:border-gold"
        onClick={() => onOpen(request)}
        aria-label={`${request.number} ${request.title}`}
      >
        <span className="icon-tile" aria-hidden="true">
          <Icon size={17} />
        </span>
        <span className="min-w-0 flex-1 basis-[180px]">
          <span className="flex items-center gap-1.5">
            <b className="truncate text-[12px] font-semibold text-ivory" title={request.title}>
              {request.title}
            </b>
            <DemoBadge demo={request.demo} />
          </span>
          <small className="mt-0.5 block truncate text-[10px] text-muted">
            <span className="font-semibold text-gold-light/80">{request.number}</span>
            {' • '}
            {request.categoryName}
            {showRequester && request.requesterName ? ` • ${shortName(request.requesterName)}` : ''}
          </small>
        </span>
        <span className="ml-[50px] flex flex-wrap items-center gap-2 sm:ml-0 sm:flex-nowrap">
          {request.commentCount > 0 && (
            <span className="inline-flex items-center gap-1 text-[10px] text-muted" title={`${request.commentCount} comments`}>
              <MessageSquare size={12} aria-hidden="true" />
              <span className="sr-only">Comments:</span>
              {request.commentCount}
            </span>
          )}
          <span className="sm:w-[68px] sm:text-center">
            <PriorityBadge priority={request.priority} />
          </span>
          <span className="sm:w-[112px] sm:text-center">
            <StatusBadge status={request.status} />
          </span>
          <time className="text-[10px] whitespace-nowrap text-muted sm:w-[70px] sm:text-right" dateTime={request.createdAt} title={formatDateTime(request.createdAt)}>
            {formatRelative(request.createdAt)}
          </time>
        </span>
      </button>
    </li>
  );
}
