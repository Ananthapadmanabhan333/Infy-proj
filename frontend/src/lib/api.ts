import axios from 'axios';
export const api = axios.create({baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080/api', timeout: 5000});
api.interceptors.request.use(c=>{const token=localStorage.getItem('nexus_token'); if(token)c.headers.Authorization=`Bearer ${token}`; return c;});
