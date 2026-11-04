import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, postJson, putJson } from '../lib/api';
import type { DepartmentDto, PageResponse, Role, UserDto, UserSummary } from '../lib/types';
import { STALE } from './common';

export interface UserListParams {
  search?: string;
  role?: string;
  departmentId?: string;
  active?: string;
  page?: number;
  size?: number;
}

export interface CreateUserBody {
  email: string;
  fullName: string;
  role: Role;
  departmentId: number | null;
  jobTitle: string | null;
  phone: string | null;
  password: string;
}

export interface UpdateUserBody {
  fullName: string;
  role: Role;
  departmentId: number | null;
  jobTitle: string | null;
  phone: string | null;
  active: boolean;
}

export interface DepartmentBody {
  code: string;
  name: string;
  description: string | null;
  managerId: number | null;
  email: string | null;
}

export function useUsers(params: UserListParams, enabled = true) {
  return useQuery({
    queryKey: ['users', 'list', params],
    queryFn: () => getJson<PageResponse<UserDto>>('/users', { ...params }),
    placeholderData: keepPreviousData,
    staleTime: STALE.list,
    enabled,
  });
}

export function useAssignableUsers(departmentId: number | null | undefined, enabled = true) {
  return useQuery({
    queryKey: ['users', 'assignable', departmentId ?? null],
    queryFn: () => getJson<UserSummary[]>('/users/assignable', { departmentId: departmentId ?? undefined }),
    staleTime: STALE.standard,
    enabled,
  });
}

export function useDepartments(enabled = true) {
  return useQuery({
    queryKey: ['departments'],
    queryFn: () => getJson<DepartmentDto[]>('/departments'),
    staleTime: STALE.reference,
    enabled,
  });
}

export function useCreateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateUserBody) => postJson<UserDto>('/users', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  });
}

export function useUpdateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: UpdateUserBody }) => putJson<UserDto>(`/users/${id}`, body),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['users'] }),
        queryClient.invalidateQueries({ queryKey: ['departments'] }),
      ]),
  });
}

export function useResetPassword() {
  return useMutation({
    mutationFn: ({ id, newPassword }: { id: number; newPassword: string }) => postJson<void>(`/users/${id}/reset-password`, { newPassword }),
  });
}

export function useSaveDepartment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id?: number; body: DepartmentBody }) =>
      id ? putJson<DepartmentDto>(`/departments/${id}`, body) : postJson<DepartmentDto>('/departments', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['departments'] }),
  });
}
