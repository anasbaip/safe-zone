export interface Product {
    id?: string;
    name: string;
    description: string;
    category?: string;
    price: number;
    ownerId?: string;
    images?: string[];
    mediaIds?: string[];
    createdAt?: string;
    quantity: number;
}

export type ProductSort = 'newest' | 'featured' | 'price-asc' | 'price-desc' | 'name-asc';

export interface ProductSearchFilters {
    q?: string;
    category?: string;
    minPrice?: number | null;
    maxPrice?: number | null;
    inStock?: boolean;
    sort?: ProductSort;
    page?: number;
    size?: number;
}

export interface ProductSearchResponse {
    content: Product[];
    totalElements: number;
    totalPages: number;
    page: number;
    size: number;
}
