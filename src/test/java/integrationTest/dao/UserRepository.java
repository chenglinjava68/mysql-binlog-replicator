/*
    Copyright (C) 2015  Jean-Loup Adde

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/


package integrationTest.dao;

import integrationTest.domain.User;
import org.springframework.data.elasticsearch.repository.ElasticsearchCrudRepository;

/**
 * Created by juanwolf on 17/07/15.
 */
public interface UserRepository extends ElasticsearchCrudRepository<User, Integer> {

}
