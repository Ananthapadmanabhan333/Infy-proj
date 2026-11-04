import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { deleteJson, getJson, postJson, putJson } from '../lib/api';
import type { TeamDto, TeamMemberRole, TeamRef, TeamRequest } from '../lib/types';
import { STALE } from './common';

/** R3 teams (docs/API.md "Teams"): readers list/read, team admins maintain. */
export interface TeamListParams {
  departmentId?: number | string;
  active?: boolean | string;
  search?: string;
}

export const teamKeys = {
  all: ['teams'] as const,
  list: (params: TeamListParams) => ['teams', 'list', params] as const,
  mine: ['teams', 'mine'] as const,
  detail: (id: number) => ['teams', 'detail', id] as const,
};

export function useTeams(params: TeamListParams = {}, enabled = true) {
  return useQuery({
    queryKey: teamKeys.list(params),
    queryFn: () => getJson<TeamDto[]>('/teams', { ...params, active: params.active === '' ? undefined : params.active }),
    staleTime: STALE.standard,
    enabled,
  });
}

/** Active teams the caller belongs to (any user). */
export function useMyTeams(enabled = true) {
  return useQuery({
    queryKey: teamKeys.mine,
    queryFn: () => getJson<TeamRef[]>('/teams/mine'),
    staleTime: STALE.reference,
    enabled,
  });
}

export function useTeam(id: number | null | undefined, enabled = true) {
  return useQuery({
    queryKey: teamKeys.detail(id ?? 0),
    queryFn: () => getJson<TeamDto>(`/teams/${id}`),
    staleTime: STALE.standard,
    enabled: enabled && Boolean(id),
  });
}

function invalidateTeams(queryClient: QueryClient, team?: TeamDto | null) {
  if (team && typeof team === 'object' && 'id' in team) queryClient.setQueryData(teamKeys.detail(team.id), team);
  return queryClient.invalidateQueries({ queryKey: teamKeys.all });
}

export function useSaveTeam() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: TeamRequest }) => (id ? putJson<TeamDto>(`/teams/${id}`, body) : postJson<TeamDto>('/teams', body)),
    onSuccess: (team) => invalidateTeams(queryClient, team),
  });
}

export function useAddTeamMember(teamId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { userId: number; memberRole?: TeamMemberRole }) => postJson<TeamDto>(`/teams/${teamId}/members`, body),
    onSuccess: (team) => invalidateTeams(queryClient, team),
  });
}

export function useUpdateTeamMember(teamId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, memberRole }: { userId: number; memberRole: TeamMemberRole }) => putJson<TeamDto>(`/teams/${teamId}/members/${userId}`, { memberRole }),
    onSuccess: (team) => invalidateTeams(queryClient, team),
  });
}

export function useRemoveTeamMember(teamId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (userId: number) => deleteJson(`/teams/${teamId}/members/${userId}`),
    onSuccess: () => invalidateTeams(queryClient),
  });
}
